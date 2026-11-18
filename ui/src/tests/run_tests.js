/**
 * NovaClaw Comprehensive Unit & Integration Test Suite
 * Tests Pruning, Safety Guards, Verification Diffs, and the Full Agent Loop.
 */

const { SemanticUIPruner } = require('../engine/SemanticUIPruner');
const { SafetyShield } = require('../engine/SafetyShield');
const { ActionVerifier } = require('../engine/ActionVerifier');
const { ModelEngine } = require('../engine/ModelEngine');
const { AgentLoop } = require('../engine/AgentLoop');
const { MOCK_APPS } = require('../data/mockApps');

let passedTests = 0;
let totalTests = 0;

function assert(condition, message) {
  totalTests++;
  if (!condition) {
    console.error(`❌ FAILED: ${message}`);
    process.exit(1);
  } else {
    passedTests++;
    console.log(`✓ PASSED: ${message}`);
  }
}

async function runAllTests() {
  console.log('====================================================');
  console.log('   NovaClaw Autonomous Engine Verification Suite    ');
  console.log('====================================================\n');

  // TEST 1: SemanticUIPruner reduces tree size and formats SoM marks
  console.log('--- TEST GROUP 1: Semantic UI Pruning ---');
  const pruner = new SemanticUIPruner();
  const pruned = pruner.prune(MOCK_APPS.WHATSAPP_CHAT_LIST, {
    packageName: 'com.whatsapp',
    appName: 'WhatsApp'
  });

  assert(pruned.elements.length > 0, 'Pruner extracted interactive elements');
  assert(pruned.promptString.includes('[Current Screen: WhatsApp'), 'Prompt string includes screen context');
  assert(pruned.promptString.includes('[1] TEXT "WhatsApp"'), 'Text elements properly indexed');
  assert(pruned.elementMap.has(7), 'Element map contains contact chat item');
  assert(pruned.stateHash.startsWith('0x'), 'Generates valid deterministic state hash');

  // TEST 2: SafetyShield Package Firewall & Destructive Patterns
  console.log('\n--- TEST GROUP 2: Quad-Shield Safety Architecture ---');
  const safety = new SafetyShield();

  // Test 2a: Safe WhatsApp Click
  const safeAction = {
    type: 'click',
    targetElement: { id: 7, label: 'Sarah Jenkins' }
  };
  const safeEval = safety.evaluate(safeAction, { packageName: 'com.whatsapp' });
  assert(safeEval.allowed === true, 'Safe WhatsApp click permitted');

  // Test 2b: Dangerous Wipe/Format Action
  const destructiveAction = {
    type: 'click',
    targetElement: { id: 99, label: 'Delete Bank Account & Wipe Data' }
  };
  const destructiveEval = safety.evaluate(destructiveAction, { packageName: 'com.finance.bankwallet' });
  assert(destructiveEval.allowed === false, 'Destructive wipe action blocked');
  assert(destructiveEval.requiresConfirmation === true, 'Destructive action flagged for HITL confirmation');
  assert(destructiveEval.riskLevel === 'CRITICAL' || destructiveEval.riskLevel === 'HIGH', 'Risk level marked HIGH/CRITICAL');

  // Test 2c: Emergency Kill-Switch
  safety.triggerKillSwitch();
  const killSwitchEval = safety.evaluate(safeAction, { packageName: 'com.whatsapp' });
  assert(killSwitchEval.allowed === false, 'Emergency kill-switch blocks all subsequent actions');
  safety.reset();

  // TEST 3: ActionVerifier Dual-State & Diffing
  console.log('\n--- TEST GROUP 3: Action Verification & State Diff ---');
  const verifier = new ActionVerifier();

  const preState = pruner.prune(MOCK_APPS.WHATSAPP_CHAT_LIST, { packageName: 'com.whatsapp' });
  const postState = pruner.prune(MOCK_APPS.WHATSAPP_CHAT_CONVERSATION, { packageName: 'com.whatsapp' });

  // Test 3a: Successful screen transition
  const clickAction = { type: 'click', targetId: 7, label: 'Sarah Jenkins' };
  const clickVerify = verifier.verify(clickAction, preState, postState);
  assert(clickVerify.success === true, 'Verified state transition between screens');
  assert(clickVerify.status === 'VERIFIED', 'Verification marked as VERIFIED');

  // Test 3b: No-op / Dead click detection
  const deadClickVerify = verifier.verify(clickAction, preState, preState);
  assert(deadClickVerify.success === false, 'Detected zero state change on dead click');
  assert(deadClickVerify.status === 'NO_STATE_CHANGE', 'Status correctly classified as NO_STATE_CHANGE');

  // TEST 4: Model Engine Inference & Structured Output
  console.log('\n--- TEST GROUP 4: Model Engine & Gemma 4 Host ---');
  const modelEngine = new ModelEngine();
  const selected = modelEngine.getSelectedModel();
  assert(selected.id === 'gemma-4-2b-it', 'Default model is Google Gemma 4 2B');

  const inferenceResult = await modelEngine.generateActionStep({
    userGoal: "Send a message to Sarah on WhatsApp saying 'Let's meet at 5'",
    prunedState: preState,
    history: []
  });
  assert(inferenceResult.thought !== undefined, 'Model generated Antigravity-style internal thought');
  assert(inferenceResult.action === 'click', 'Model correctly decided to click contact');
  assert(inferenceResult.target_id === 7, 'Model correctly targeted Sarah Jenkins mark [7]');

  // Test 4b: Conversational Greeting ("Hu" / "Hi") handled gracefully on-device
  const huResult = await modelEngine.generateActionStep({
    userGoal: "Hu",
    prunedState: { packageName: 'com.android.launcher3', elements: [] },
    history: []
  });
  assert(huResult.isConversational === true, 'Model correctly recognized "Hu" as on-device greeting');
  assert(huResult.payload.includes('NovaClaw'), 'Model introduced itself friendly without error');

  // Test 4c: Generic App Launching ("Open Camera")
  const cameraResult = await modelEngine.generateActionStep({
    userGoal: "Open Camera",
    prunedState: { packageName: 'com.android.launcher3', elements: [] },
    history: []
  });
  assert(cameraResult.action === 'launch_app', 'Model correctly determined launch_app action');
  assert(cameraResult.payload.includes('camera'), 'Model targeted camera package');

  // TEST 5: Full End-to-End Agent Loop Simulation
  console.log('\n--- TEST GROUP 5: Autonomous End-to-End Agent Loop ---');
  
  // Create simulated mock device environment
  class MockDeviceEnvironment {
    constructor() {
      this.currentScreen = MOCK_APPS.HOME_SCREEN;
    }
    getCurrentScreen() {
      return this.currentScreen;
    }
    async executeAction(action) {
      if (action.type === 'launch_app' && action.payload === 'com.whatsapp') {
        this.currentScreen = MOCK_APPS.WHATSAPP_CHAT_LIST;
      } else if (action.type === 'click' && action.targetId === 7) {
        this.currentScreen = MOCK_APPS.WHATSAPP_CHAT_CONVERSATION;
      } else if (action.type === 'type') {
        // Appends text into conversation
      } else if (action.type === 'click' && (action.targetId === 11 || action.targetId === 'send')) {
        this.currentScreen = {
          ...MOCK_APPS.WHATSAPP_CHAT_CONVERSATION,
          elements: [
            ...MOCK_APPS.WHATSAPP_CHAT_CONVERSATION.elements,
            { id: 12, type: 'text', text: 'Hey! Are we still meeting at 5:00 PM? (Sent ✓✓)', bounds: { x: 120, y: 350, width: 250, height: 40 } }
          ]
        };
      }
    }
  }

  const mockEnv = new MockDeviceEnvironment();
  let stepCount = 0;
  let taskCompleted = false;

  const agent = new AgentLoop({
    pruner,
    safetyShield: new SafetyShield(),
    verifier: new ActionVerifier({ settleDelayMs: 10 }),
    modelEngine,
    environment: mockEnv,
    maxSteps: 8,
    onStep: () => { stepCount++; },
    onComplete: (res) => {
      taskCompleted = true;
      assert(res.success === true, 'Agent finished task successfully');
    }
  });

  await agent.start("Send a message to Sarah on WhatsApp saying 'Let's meet at 5'");
  assert(taskCompleted, 'Agent loop ran through all stages to completion');
  assert(stepCount >= 3, `Executed multi-step plan (took ${stepCount} steps)`);

  console.log('\n====================================================');
  console.log(`  ALL ${passedTests}/${totalTests} TESTS PASSED WITH 0 ERRORS! `);
  console.log('====================================================\n');
}

runAllTests().catch(err => {
  console.error('Fatal Test Exception:', err);
  process.exit(1);
});
