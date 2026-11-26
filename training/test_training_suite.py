"""Unit tests verifying dataset integrity, split isolation, NovaClaw TaskDecision
schema compliance, and GRPO self-healing reward behavior.
"""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from build_dataset import build_chat_replay_samples, generate_scenarios, scenario_to_grpo_record, scenario_to_sft_record
from novaclaw_spec import (
    DEFAULT_AGENT_TOOLS,
    extract_tap_nodes_from_prompt,
    parse_task_decision,
    reward_expected_action,
    reward_format_and_thinking,
    reward_grounding_and_safety,
)


class NovaClawTrainingSuiteTest(unittest.TestCase):
    def test_all_sft_agent_samples_are_valid_grounded_and_safe(self) -> None:
        scenarios = generate_scenarios(400, seed=123, is_eval=False)
        categories = {sc.category for sc in scenarios}
        self.assertIn("recovery_stale_node", categories)
        self.assertIn("recovery_unconfirmed_tap", categories)
        self.assertIn("recovery_focus_before_type", categories)
        self.assertIn("recovery_submit_control", categories)
        self.assertIn("recovery_popup_dismiss", categories)
        self.assertIn("recovery_bottom_pivot_search", categories)
        self.assertIn("safety_send_vs_call", categories)
        self.assertIn("safety_prompt_injection", categories)

        for sc in scenarios:
            row = scenario_to_sft_record(sc)
            comp = row["completion"][0]["content"]
            parsed = parse_task_decision(comp, sc.tool_names)
            self.assertIsNotNone(parsed, f"Invalid TaskDecision in {sc.scenario_id}: {comp}")
            assert parsed is not None

            tap_map = extract_tap_nodes_from_prompt(row["prompt"][1]["content"])
            if parsed["tool"] == "tap_node":
                nid = parsed["args"]["node_id"]
                self.assertIn(nid, tap_map, f"{sc.scenario_id} tapped ungrounded node {nid}")
                self.assertNotIn(nid, sc.forbidden_node_ids, f"{sc.scenario_id} tapped forbidden node {nid}")

    def test_grpo_rewards_prefer_self_healing_and_penalize_loops(self) -> None:
        sc = next(
            s for s in generate_scenarios(100, seed=7, is_eval=False)
            if s.category == "recovery_unconfirmed_tap"
        )
        row = scenario_to_grpo_record(sc)
        gold = row["expected_actions"][0]
        gold_json = json.dumps({"tool": gold["tool"], "args": gold["args"]})
        repeat_failed_json = json.dumps({"tool": "tap_node", "args": {"node_id": sc.forbidden_node_ids[0]}})
        hallucinated_json = json.dumps({"tool": "tap_node", "args": {"node_id": "n999"}})
        premature_finish_json = json.dumps({"tool": "finish", "args": {"summary": "Done!"}})
        prose_bad = "I will tap the button for you now."

        completions = [
            f"<|channel>thought\nPrevious tap failed; try alternate button.<channel|>{gold_json}",
            repeat_failed_json,
            hallucinated_json,
            premature_finish_json,
            prose_bad,
        ]
        n = len(completions)
        fmt = reward_format_and_thinking(completions, allowed_tools=[row["allowed_tools"]] * n)
        grd = reward_grounding_and_safety(
            completions,
            tap_nodes=[row["tap_nodes"]] * n,
            package_name=[row["package_name"]] * n,
            read_screen_allowed=[row["read_screen_allowed"]] * n,
            action_succeeded=[row["action_succeeded"]] * n,
            last_failed_action=[row["last_failed_action"]] * n,
            forbidden_node_ids=[row["forbidden_node_ids"]] * n,
            allowed_tools=[row["allowed_tools"]] * n,
        )
        act = reward_expected_action(
            completions,
            expected_actions=[row["expected_actions"]] * n,
            acceptable_tools=[row["acceptable_tools"]] * n,
            tap_nodes=[row["tap_nodes"]] * n,
            last_failed_action=[row["last_failed_action"]] * n,
            forbidden_node_ids=[row["forbidden_node_ids"]] * n,
            read_screen_allowed=[row["read_screen_allowed"]] * n,
            action_succeeded=[row["action_succeeded"]] * n,
            allowed_tools=[row["allowed_tools"]] * n,
        )
        totals = [f + g + a for f, g, a in zip(fmt, grd, act)]

        # Gold self-healing recovery gets full 0.5 + 1.0 + 2.5 = 4.0
        self.assertAlmostEqual(totals[0], 4.0)
        # Repeating the forbidden/failed node is negative (0.5 - 0.8 + 0.0 = -0.3)
        self.assertLess(totals[1], 0.0)
        # Hallucinated node ID penalized (0.5 - 0.5 + 0.0 = 0.0)
        self.assertLessEqual(totals[2], 0.0)
        # Premature finish without evidence penalized (0.5 - 0.6 + 0.0 = -0.1)
        self.assertLess(totals[3], 0.0)
        # Prose instead of strict JSON scores 0
        self.assertAlmostEqual(totals[4], 0.0)

    def test_generated_jsonl_splits_have_zero_prompt_leakage(self) -> None:
        from build_dataset import main as build_main
        import sys

        with tempfile.TemporaryDirectory() as tmp:
            old_argv = sys.argv
            try:
                sys.argv = [
                    "build_dataset.py",
                    "--out-dir", tmp,
                    "--sft-count", "200",
                    "--grpo-count", "100",
                    "--eval-count", "60",
                ]
                build_main()
            finally:
                sys.argv = old_argv

            def load_user_prompts(fname: str) -> set[str]:
                lines = (Path(tmp) / fname).read_text(encoding="utf-8").splitlines()
                return {json.loads(ln)["prompt"][1]["content"] for ln in lines if ln.strip()}

            sft_train_p = load_user_prompts("sft_train.jsonl")
            sft_val_p = load_user_prompts("sft_val.jsonl")
            grpo_train_p = load_user_prompts("grpo_train.jsonl")
            grpo_eval_p = load_user_prompts("grpo_eval.jsonl")

            self.assertTrue(sft_train_p.isdisjoint(sft_val_p))
            self.assertTrue(grpo_train_p.isdisjoint(grpo_eval_p))
            self.assertGreater(len(build_chat_replay_samples()), 0)
            self.assertIsNone(parse_task_decision("How can I help you?", DEFAULT_AGENT_TOOLS))


if __name__ == "__main__":
    unittest.main()
