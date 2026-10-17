package cn.rolechat.core

/** Validate optional saved configuration without changing legacy rooms' reply mode. */
fun validateRoutingSettings(settings: RoutingSettings, providers: List<Provider>? = null) {
    require(settings.model.providerId.isNotBlank() && settings.model.model.isNotBlank()) { "请选择接话判断模型" }
    require(settings.model.model.length <= 256 && settings.model.providerId.length <= 256) { "接话判断模型名称或服务 ID 不能超过 256 个字符" }
    require(providers == null || providers.any { it.id == settings.model.providerId }) { "请选择有效的接话判断模型服务" }
    require(settings.systemPrompt.isNotBlank() && settings.systemPrompt.length <= 32768) { "接话判断提示词不能为空，且不能超过 32768 个字符" }
    require(settings.recentMessages in 1..20) { "接话判断最近消息数需在 1 到 20 条之间" }
    require(settings.maxAutoReplies in 1..20) { "智能接话每轮发言上限需在 1 到 20 条之间" }
    require(settings.model.maxTokens in 128..2048) { "接话判断最大输出需在 128 到 2048 之间" }
    require(settings.model.temperature.isFinite() && settings.model.temperature in 0.0..2.0) { "接话判断温度需在 0 到 2 之间" }
}
