package com.saltin.screentranslator.translate

/**
 * 云端接口协议族。同一个协议族的服务商共用一套请求/解析代码。
 *
 * - [GEMINI]    Google Generative Language API（`generateContent`）
 * - [OPENAI]    OpenAI Chat Completions（绝大多数国内/自建服务都兼容此协议，含 Ollama、vLLM、One-API）
 * - [ANTHROPIC] Anthropic Messages API
 */
enum class LlmProtocol(val id: String, val label: String) {
    GEMINI("gemini", "Google Gemini 协议"),
    OPENAI("openai", "OpenAI 兼容协议"),
    ANTHROPIC("anthropic", "Anthropic Claude 协议"),
}

/**
 * 一个可选的云端模型服务商。
 *
 * [models] 只是**预设候选**，界面上始终允许手填任意模型名 ——
 * 各家的模型迭代很快，写死的列表一定会过时，所以预设只是方便，不作为限制。
 */
data class LlmProvider(
    val id: String,
    val label: String,
    val protocol: LlmProtocol,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val models: List<String>,
    /** 该服务商是否有可用的「看图」模型；不支持时混合/多模态模式会自动退化为纯文本路径 */
    val supportsVision: Boolean,
    val keyHint: String,
    val consoleUrl: String,
    /** 设置页里显示的一句话说明 */
    val note: String,
    /**
     * 必须随每个请求一起发送的附加参数（JSON 对象字符串）。
     *
     * 主要用途是抹平各家不统一的接口怪癖 —— 典型例子就是阿里云百炼：
     * 它的 OpenAI 兼容端点对 Qwen3 系列要求**非流式请求必须显式传
     * `enable_thinking: false`**，不传就直接 400
     * （`parameter.enable_thinking must be set to false for non-streaming calls`），
     * 或者把正文写进 `reasoning_content` 让 `content` 变成空字符串。
     */
    val extraBodyJson: String = "",
)

object LlmProviders {

    val GEMINI = LlmProvider(
        id = "gemini",
        label = "Google Gemini（推荐）",
        protocol = LlmProtocol.GEMINI,
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
        defaultModel = "gemini-2.5-flash",
        models = listOf(
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemini-2.5-pro",
            "gemini-2.0-flash",
        ),
        supportsVision = true,
        keyHint = "AIza 开头",
        consoleUrl = "https://aistudio.google.com/apikey",
        note = "免费额度友好、看图能力强、中文质量好，综合体验最佳。",
    )

    val OPENAI = LlmProvider(
        id = "openai",
        label = "OpenAI",
        protocol = LlmProtocol.OPENAI,
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-4o-mini",
        models = listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "gpt-4.1"),
        supportsVision = true,
        keyHint = "sk- 开头",
        consoleUrl = "https://platform.openai.com/api-keys",
        note = "需要海外网络环境；国内直连通常不通，可改用下面任意一家国产服务。",
    )

    val DEEPSEEK = LlmProvider(
        id = "deepseek",
        label = "DeepSeek 深度求索",
        protocol = LlmProtocol.OPENAI,
        defaultBaseUrl = "https://api.deepseek.com/v1",
        defaultModel = "deepseek-chat",
        models = listOf("deepseek-chat"),
        supportsVision = false,
        keyHint = "sk- 开头",
        consoleUrl = "https://platform.deepseek.com/api_keys",
        note = "便宜、快、中文好，但**不支持看图**，只能用「文字 + 截图」里的纯文字路径（会自动切换）。",
    )

    val QWEN = LlmProvider(
        id = "qwen",
        label = "阿里云通义千问（百炼）",
        protocol = LlmProtocol.OPENAI,
        defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        defaultModel = "qwen3.6-plus",
        models = listOf(
            "qwen3.6-plus",
            "qwen3.6-flash",
            "qwen3.8-max",
            "qwen-vl-max-latest",
            "qwen-vl-plus",
            "qwen-plus",
            "qwen-flash",
        ),
        supportsVision = true,
        keyHint = "sk- 开头",
        consoleUrl = "https://bailian.console.aliyun.com/",
        note = "国内直连稳定，有免费额度。vl 系列支持看图；qwen3.x 系列是思考模型，" +
            "本应用已自动注入 enable_thinking=false（非流式调用的硬要求），无需你手动处理。",
        // ★ 关键：百炼对 Qwen3 系列要求非流式请求显式关闭思考，否则 400 / 正文为空
        extraBodyJson = """{"enable_thinking":false}""",
    )

    val ZHIPU = LlmProvider(
        id = "zhipu",
        label = "智谱 GLM",
        protocol = LlmProtocol.OPENAI,
        defaultBaseUrl = "https://open.bigmodel.cn/api/paas/v4",
        defaultModel = "glm-4v-flash",
        models = listOf("glm-4v-flash", "glm-4v-plus", "glm-4-flash", "glm-4-plus"),
        supportsVision = true,
        keyHint = "形如 xxxx.xxxx",
        consoleUrl = "https://bigmodel.cn/usercenter/apikeys",
        note = "glm-4v-flash 有免费额度且支持看图，性价比很高。",
    )

    val MOONSHOT = LlmProvider(
        id = "moonshot",
        label = "月之暗面 Kimi",
        protocol = LlmProtocol.OPENAI,
        defaultBaseUrl = "https://api.moonshot.cn/v1",
        defaultModel = "moonshot-v1-8k-vision-preview",
        models = listOf("moonshot-v1-8k-vision-preview", "moonshot-v1-32k-vision-preview"),
        supportsVision = true,
        keyHint = "sk- 开头",
        consoleUrl = "https://platform.moonshot.cn/console/api-keys",
        note = "长文本与中文语境理解好，vision 后缀的模型支持看图。",
    )

    val SILICONFLOW = LlmProvider(
        id = "siliconflow",
        label = "硅基流动 SiliconFlow",
        protocol = LlmProtocol.OPENAI,
        defaultBaseUrl = "https://api.siliconflow.cn/v1",
        defaultModel = "Qwen/Qwen2.5-VL-72B-Instruct",
        models = listOf(
            "Qwen/Qwen2.5-VL-72B-Instruct",
            "Qwen/Qwen2.5-VL-32B-Instruct",
            "Qwen/Qwen2.5-7B-Instruct",
        ),
        supportsVision = true,
        keyHint = "sk- 开头",
        consoleUrl = "https://cloud.siliconflow.cn/account/ak",
        note = "聚合了大量开源模型，注册送额度，适合想省钱又想要多模态的场景。",
    )

    val OPENROUTER = LlmProvider(
        id = "openrouter",
        label = "OpenRouter（聚合中转）",
        protocol = LlmProtocol.OPENAI,
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        defaultModel = "google/gemini-2.5-flash",
        models = listOf(
            "google/gemini-2.5-flash",
            "openai/gpt-4o-mini",
            "anthropic/claude-3.5-sonnet",
            "qwen/qwen-2.5-vl-72b-instruct",
        ),
        supportsVision = true,
        keyHint = "sk-or- 开头",
        consoleUrl = "https://openrouter.ai/keys",
        note = "一个 Key 通吃几十家模型，方便横向比较效果。",
    )

    val ANTHROPIC = LlmProvider(
        id = "anthropic",
        label = "Anthropic Claude",
        protocol = LlmProtocol.ANTHROPIC,
        defaultBaseUrl = "https://api.anthropic.com/v1",
        defaultModel = "claude-3-5-haiku-latest",
        models = listOf(
            "claude-3-5-haiku-latest",
            "claude-3-5-sonnet-latest",
            "claude-3-7-sonnet-latest",
        ),
        supportsVision = true,
        keyHint = "sk-ant- 开头",
        consoleUrl = "https://console.anthropic.com/settings/keys",
        note = "译文自然度高；需要海外网络环境。",
    )

    val OLLAMA = LlmProvider(
        id = "ollama",
        label = "本地 Ollama（电脑局域网）",
        protocol = LlmProtocol.OPENAI,
        defaultBaseUrl = "http://192.168.1.100:11434/v1",
        defaultModel = "qwen2.5vl:7b",
        models = listOf("qwen2.5vl:7b", "llava:7b", "minicpm-v", "qwen2.5:7b"),
        supportsVision = true,
        keyHint = "随便填，Ollama 不校验",
        consoleUrl = "https://ollama.com/download",
        note = "完全免费、数据不出内网。需要把地址改成电脑在局域网的 IP（不是 127.0.0.1），" +
            "并让 Ollama 监听 0.0.0.0：设置环境变量 OLLAMA_HOST=0.0.0.0 后重启。",
    )

    val CUSTOM = LlmProvider(
        id = "custom",
        label = "自定义 / 其他兼容接口",
        protocol = LlmProtocol.OPENAI,
        defaultBaseUrl = "",
        defaultModel = "",
        models = emptyList(),
        supportsVision = true,
        keyHint = "按你的服务商要求填写",
        consoleUrl = "",
        note = "任何 OpenAI 兼容的 /chat/completions 接口都可以：One-API、New-API、vLLM、" +
            "LM Studio、公司内部网关等。地址填到 /v1 为止。",
    )

    /** 顺序即设置页下拉框的顺序，把最常用的放在前面 */
    val all: List<LlmProvider> = listOf(
        GEMINI,
        QWEN,
        ZHIPU,
        DEEPSEEK,
        MOONSHOT,
        SILICONFLOW,
        OPENROUTER,
        OPENAI,
        ANTHROPIC,
        OLLAMA,
        CUSTOM,
    )

    fun byId(id: String?): LlmProvider = all.firstOrNull { it.id == id } ?: GEMINI

    /**
     * 规整 baseUrl。除了去掉末尾斜杠，还要容错用户直接粘贴的完整接口地址 ——
     * 很多人会从文档里复制 `…/compatible-mode/v1/chat/completions` 整条，
     * 直接拼会变成 `…/chat/completions/chat/completions` 然后 404。
     */
    fun normalizeBaseUrl(raw: String): String = raw.trim()
        .trimEnd('/')
        .removeSuffix("/chat/completions")
        .removeSuffix("/messages")
        .removeSuffix("/completions")
        .trimEnd('/')
}
