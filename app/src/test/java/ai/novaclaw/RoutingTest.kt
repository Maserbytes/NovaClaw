package ai.novaclaw

import ai.novaclaw.engine.ConversationRouter
import ai.novaclaw.engine.PipelineRouter
import ai.novaclaw.engine.TaskShortcuts
import org.junit.Assert.*
import org.junit.Test

/** Routing tests — the most bug-prone area (wrong route = "Hello! How can I help?"). */
class RoutingTest {

    // ---------- ConversationRouter.isChatOnly — 30 tests ----------
    @Test fun chat_hi() = assertTrue(ConversationRouter.isChatOnly("Hi"))
    @Test fun chat_hello() = assertTrue(ConversationRouter.isChatOnly("Hello"))
    @Test fun chat_hey() = assertTrue(ConversationRouter.isChatOnly("Hey there"))
    @Test fun chat_howAreYou() = assertTrue(ConversationRouter.isChatOnly("How are you?"))
    @Test fun chat_whatsUp() = assertTrue(ConversationRouter.isChatOnly("What's up?"))
    @Test fun chat_albert() = assertTrue(ConversationRouter.isChatOnly("Who is Albert Einstein"))
    @Test fun chat_roman() = assertTrue(ConversationRouter.isChatOnly("Tell me about the Roman Empire"))
    @Test fun chat_quantum() = assertTrue(ConversationRouter.isChatOnly("Explain quantum computing in simple terms"))
    @Test fun chat_poem() = assertTrue(ConversationRouter.isChatOnly("Write a short poem about rain"))
    @Test fun chat_math() = assertTrue(ConversationRouter.isChatOnly("What is 6+82-7+269"))
    @Test fun chat_code() = assertTrue(ConversationRouter.isChatOnly("Write Kotlin code to sort a list"))
    @Test fun chat_translate() = assertTrue(ConversationRouter.isChatOnly("Translate hello to French"))
    @Test fun chat_why() = assertTrue(ConversationRouter.isChatOnly("Why is the sky blue?"))
    @Test fun chat_howQuestion() = assertTrue(ConversationRouter.isChatOnly("How do airplanes fly?"))
    @Test fun chat_opinion() = assertTrue(ConversationRouter.isChatOnly("What do you think about AI?"))
    @Test fun chat_recipe() = assertTrue(ConversationRouter.isChatOnly("Give me a recipe for pasta"))
    @Test fun chat_joke() = assertTrue(ConversationRouter.isChatOnly("Tell me a joke"))
    @Test fun chat_weatherQuestion() = assertTrue(ConversationRouter.isChatOnly("Is it going to rain tomorrow?"))
    @Test fun chat_history() = assertTrue(ConversationRouter.isChatOnly("Who invented the telephone?"))
    @Test fun chat_science() = assertTrue(ConversationRouter.isChatOnly("Explain photosynthesis"))
    @Test fun chat_blank() = assertTrue(ConversationRouter.isChatOnly(""))
    @Test fun chat_spaces() = assertTrue(ConversationRouter.isChatOnly("   "))
    @Test fun chat_punctuation() = assertTrue(ConversationRouter.isChatOnly("???"))
    @Test fun chat_caseInsensitive() = assertTrue(ConversationRouter.isChatOnly("WHO IS NEIL ARMSTRONG"))
    @Test fun chat_longQuestion() = assertTrue(ConversationRouter.isChatOnly("Can you please explain in detail how neural networks learn from data?")) 
    @Test fun action_openWhatsapp() = assertFalse(ConversationRouter.isChatOnly("Open WhatsApp"))
    @Test fun action_sendMessage() = assertFalse(ConversationRouter.isChatOnly("Send hi to Sarah on WhatsApp"))
    @Test fun action_searchInApp() = assertFalse(ConversationRouter.isChatOnly("Search in YouTube for lofi"))
    @Test fun action_turnOn() = assertFalse(ConversationRouter.isChatOnly("Turn on Battery Saver"))
    @Test fun action_takeScreenshot() = assertFalse(ConversationRouter.isChatOnly("Take a screenshot"))

    // ---------- ConversationRouter.isPureOpen — 15 tests ----------
    @Test fun pureOpen_whatsapp() = assertTrue(ConversationRouter.isPureOpen("Open WhatsApp"))
    @Test fun pureOpen_camera() = assertTrue(ConversationRouter.isPureOpen("Launch Camera"))
    @Test fun pureOpen_youtube() = assertTrue(ConversationRouter.isPureOpen("Start YouTube"))
    @Test fun pureOpen_lowercase() = assertTrue(ConversationRouter.isPureOpen("open whatsapp"))
    @Test fun pureOpen_maps() = assertTrue(ConversationRouter.isPureOpen("Open Google Maps"))
    @Test fun pureOpen_singleWord() = assertTrue(ConversationRouter.isPureOpen("Open Chrome"))
    @Test fun pureOpen_withSpaces() = assertTrue(ConversationRouter.isPureOpen("  Open Settings  "))
    @Test fun notPureOpen_andSend() = assertFalse(ConversationRouter.isPureOpen("Open WhatsApp and send message to Sarah"))
    @Test fun notPureOpen_then() = assertFalse(ConversationRouter.isPureOpen("Open Settings then turn on wifi"))
    @Test fun notPureOpen_to() = assertFalse(ConversationRouter.isPureOpen("Open WhatsApp to Sarah"))
    @Test fun notPureOpen_fourWords() = assertFalse(ConversationRouter.isPureOpen("Open phone settings please now"))
    @Test fun notPureOpen_goHome() = assertFalse(ConversationRouter.isPureOpen("go home"))
    @Test fun notPureOpen_question() = assertFalse(ConversationRouter.isPureOpen("What apps are installed?"))
    @Test fun notPureOpen_empty() = assertFalse(ConversationRouter.isPureOpen(""))
    @Test fun notPureOpen_search() = assertFalse(ConversationRouter.isPureOpen("Open Chrome search for weather"))

    // ---------- PipelineRouter — 20 tests ----------
    @Test fun pipe_battery() = assertEquals(PipelineRouter.Route.DeviceInfo, PipelineRouter.route("What's my battery?"))
    @Test fun pipe_charging() = assertEquals(PipelineRouter.Route.DeviceInfo, PipelineRouter.route("Is it charging?"))
    @Test fun pipe_storage() = assertEquals(PipelineRouter.Route.DeviceInfo, PipelineRouter.route("How much storage is left?"))
    @Test fun pipe_ram() = assertEquals(PipelineRouter.Route.DeviceInfo, PipelineRouter.route("How much RAM do I have?"))
    @Test fun pipe_androidVersion() = assertEquals(PipelineRouter.Route.DeviceInfo, PipelineRouter.route("Which Android version?"))
    @Test fun pipe_deviceModel() = assertEquals(PipelineRouter.Route.DeviceInfo, PipelineRouter.route("What phone model is this?"))
    @Test fun pipe_wifi() = assertEquals(PipelineRouter.Route.DeviceInfo, PipelineRouter.route("Is wifi connected?"))
    @Test fun pipe_temperature() = assertEquals(PipelineRouter.Route.DeviceInfo, PipelineRouter.route("What's the phone temperature?"))
    @Test fun pipe_turnOnBattery() = assertEquals(PipelineRouter.Route.Agent, PipelineRouter.route("Turn on Battery Saver"))
    @Test fun pipe_openApp() = assertEquals(PipelineRouter.Route.Agent, PipelineRouter.route("Open WhatsApp"))
    @Test fun pipe_sendMessage() = assertEquals(PipelineRouter.Route.Agent, PipelineRouter.route("Send hi to Mom"))
    @Test fun pipe_whyBattery() = assertEquals(PipelineRouter.Route.Agent, PipelineRouter.route("Why is my battery low?"))
    @Test fun pipe_howStorage() = assertEquals(PipelineRouter.Route.Agent, PipelineRouter.route("How can I free storage?"))
    @Test fun pipe_should() = assertEquals(PipelineRouter.Route.Agent, PipelineRouter.route("Should I charge my phone?"))
    @Test fun pipe_explain() = assertEquals(PipelineRouter.Route.Agent, PipelineRouter.route("Can you explain battery usage?"))
    @Test fun pipe_albert() = assertEquals(PipelineRouter.Route.Agent, PipelineRouter.route("Who is Albert Einstein"))
    @Test fun pipe_chat() = assertEquals(PipelineRouter.Route.Agent, PipelineRouter.route("Hi"))
    @Test fun pipe_webSearch() = assertEquals(PipelineRouter.Route.Agent, PipelineRouter.route("Search the web for Gemini news"))
    @Test fun pipe_empty() = assertEquals(PipelineRouter.Route.Agent, PipelineRouter.route(""))
    @Test fun pipe_install() = assertEquals(PipelineRouter.Route.Agent, PipelineRouter.route("Install Telegram"))

    // ---------- TaskShortcuts — 15 tests ----------
    @Test fun shortcut_home() = assertEquals("system_key", TaskShortcuts.match("home")?.tool)
    @Test fun shortcut_goHome() = assertEquals("home", TaskShortcuts.match("go home")?.args?.get("key"))
    @Test fun shortcut_homeScreen() = assertEquals("system_key", TaskShortcuts.match("home screen")?.tool)
    @Test fun shortcut_back() = assertEquals("back", TaskShortcuts.match("back")?.args?.get("key"))
    @Test fun shortcut_goBack() = assertEquals("back", TaskShortcuts.match("go back")?.args?.get("key"))
    @Test fun shortcut_pressBack() = assertEquals("back", TaskShortcuts.match("press back")?.args?.get("key"))
    @Test fun shortcut_openWhatsapp() = assertEquals("whatsapp", TaskShortcuts.match("open whatsapp")?.args?.get("app_name"))
    @Test fun shortcut_launchCamera() = assertEquals("camera", TaskShortcuts.match("launch camera")?.args?.get("app_name"))
    @Test fun shortcut_startYoutube() = assertEquals("youtube", TaskShortcuts.match("start youtube")?.args?.get("app_name"))
    @Test fun shortcut_openMaps() = assertEquals("google maps", TaskShortcuts.match("open google maps")?.args?.get("app_name"))
    @Test fun shortcut_nullHi() = assertNull(TaskShortcuts.match("Hi"))
    @Test fun shortcut_nullSend() = assertNull(TaskShortcuts.match("open whatsapp and send message to Sarah"))
    @Test fun shortcut_nullTurn() = assertNull(TaskShortcuts.match("open settings turn on wifi"))
    @Test fun shortcut_nullLong() = assertNull(TaskShortcuts.match("open phone settings please now"))
    @Test fun shortcut_nullEmpty() = assertNull(TaskShortcuts.match(""))
}
