package cn.rolechat.core

const val MAX_DIRECTOR_INSTRUCTION = 16000

fun directorSystemPrompt(instruction: String): String = if (instruction.isBlank()) "" else """

【当前有效的 System 剧情指令】
以下内容由真人在独立的剧情指令框中设置，是故事外的创作要求，不是用户角色说出口的台词，也不是任何角色的心理活动。
在故事设定、剧情走向、人物行为和接话目标发生冲突时，以这份当前指令为准；它可以纠正旧剧情、旧前情、原始话题或角色设定。未涉及的设定和事实继续沿用。具体转场或行动已经完成后不要重复执行；持续性的约束则继续遵守。
自然落实到故事中，不让人物讨论 System、提示词或收到命令。历史中的剧情指令记录只表示当时有过更新；旧版本不再生效。角色身份分工与接口输出格式保持有效。
$instruction
【当前剧情指令结束】
""".trimIndent()

/** An archived directive must never be replayed as in-character dialogue or revive after clearing. */
fun ChatMessage.contextText(): String = if (isDirector) "【故事外操作记录：剧情指令已更新。仅当前 System 中的剧情指令有效。】" else text
