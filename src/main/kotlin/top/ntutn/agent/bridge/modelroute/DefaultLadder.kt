package top.ntutn.agent.bridge.modelroute

/**
 * 默认梯子：按能力递减排列。
 *
 * `providerModelId` 取 Traex `model/list` 返回的 `id`（如 `gpt-6.1-sol`、
 * `Doubao-Seed-Evolving`），可直接用于 `turn/start` 的 `model` 覆盖。
 * `contextWindow` 取同一接口的 `contextWindow`；窗口不随能力单调，
 * [ModelRouter] 在每次切换前单独校验目标窗口是否装得下当前上下文。
 */
object DefaultLadder {

    val tiers: List<ModelTier> =
        listOf(
            ModelTier(
                "gpt-6-astra",
                "GPT-6-Astra",
                contextWindow = 272_000,
                providerModelId = "gpt-6-astra",
            ),
            ModelTier(
                "gpt-6.1-sol",
                "GPT-6.1-Sol",
                contextWindow = 475_000,
                providerModelId = "gpt-6.1-sol",
            ),
            ModelTier(
                "gpt-5.6-sol",
                "GPT-5.6-Sol",
                contextWindow = 272_000,
                providerModelId = "gpt-5.6-sol",
            ),
            ModelTier(
                "gpt-5.6-terra",
                "GPT-5.6-Terra",
                contextWindow = 272_000,
                providerModelId = "gpt-5.6-terra",
            ),
            ModelTier(
                "deepseek-v4.1-flash",
                "DeepSeek-V4.1-Flash",
                contextWindow = 200_000,
                providerModelId = "deepseek-v4.1-flash",
            ),
            ModelTier(
                "seed-evolving",
                "Seed-Evolving",
                contextWindow = 1_000_000,
                providerModelId = "Doubao-Seed-Evolving",
            ),
        )

    fun ladder(): ModelLadder = ModelLadder(tiers)
}
