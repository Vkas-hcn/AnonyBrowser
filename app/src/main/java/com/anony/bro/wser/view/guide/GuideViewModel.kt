package com.anony.bro.wser.view.guide

import android.os.Build
import com.anony.bro.wser.base.BaseViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 引导页待办项：通知权限、默认浏览器。
 * 流程单向前进，每个步骤在本会话内最多展示一次。
 */
enum class GuideStep {
    /** Android 13+ 通知权限 */
    NOTIFICATION,

    /** 设为系统默认浏览器 */
    BROWSER,
}

/**
 * 单项权限/能力的实时状态，供过滤与全量校验使用。
 *
 * @param applicable 当前系统是否需要该项（如低版本无需通知权限）
 * @param satisfied 是否已授权 / 已设为默认浏览器
 */
data class GuideRequirementStatus(
    val step: GuideStep,
    val applicable: Boolean,
    val satisfied: Boolean,
) {
    /** 系统层面仍未满足：适用且尚未授权 */
    val isPending: Boolean get() = applicable && !satisfied
}

/**
 * 引导页 ViewModel：维护待办列表、当前展示步骤。
 *
 * 已跳过或已处理过的步骤记入 [dismissedSteps]，刷新时不再展示，避免流程回退。
 */
class GuideViewModel : BaseViewModel() {

    private val dismissedSteps = linkedSetOf<GuideStep>()

    private val _pendingSteps = MutableStateFlow<List<GuideStep>>(emptyList())
    /** 过滤后仍需用户处理的步骤（按展示顺序，且未 dismiss） */
    val pendingSteps: StateFlow<List<GuideStep>> = _pendingSteps.asStateFlow()

    private val _currentStep = MutableStateFlow<GuideStep?>(null)
    /** 当前引导页展示的步骤；null 表示无需展示（应跳转首页） */
    val currentStep: StateFlow<GuideStep?> = _currentStep.asStateFlow()

    private val _shouldNavigateToMain = MutableStateFlow(false)
    /** 引导流程结束时触发跳转首页 */
    val shouldNavigateToMain: StateFlow<Boolean> = _shouldNavigateToMain.asStateFlow()

    /** 本会话内已跳过/已处理过的步骤（只进不退） */
    fun dismissedStepsSnapshot(): Set<GuideStep> = dismissedSteps.toSet()

    /**
     * 将步骤标记为已处理（跳过、授权弹窗结束、或已满足），本会话内不再展示。
     */
    fun dismissStep(step: GuideStep) {
        dismissedSteps.add(step)
    }

    /**
     * 根据最新权限/角色状态刷新待办列表。
     * 已 dismiss 的步骤不会再次出现，保证流程单向。
     *
     * @return 过滤后的待办步骤；空列表表示引导结束
     */
    fun refreshFromStatuses(statuses: List<GuideRequirementStatus>): List<GuideStep> {
        // 已满足的步骤也记为 dismiss，避免后续刷新回退
        statuses.filter { it.satisfied || !it.applicable }.forEach { dismissedSteps.add(it.step) }

        val pending = filterPending(statuses)
            .filter { it !in dismissedSteps }

        _pendingSteps.value = pending

        val next = pickForwardStep(pending)
        _currentStep.value = next
        _shouldNavigateToMain.value = next == null
        return pending
    }

    /**
     * 跳过当前步骤：标记 dismiss 后前进到下一项；若无下一项则结束引导。
     */
    fun skipCurrentStep() {
        val current = _currentStep.value ?: return
        dismissStep(current)
        val remaining = _pendingSteps.value.filter { it != current && it !in dismissedSteps }
        _pendingSteps.value = remaining
        val next = remaining.firstOrNull()
        _currentStep.value = next
        if (next == null) {
            _shouldNavigateToMain.value = true
        }
    }

    /**
     * 当前步骤交互结束（如权限弹窗关闭）后前进，不再回到该步骤。
     */
    fun completeCurrentStepAndAdvance(statuses: List<GuideRequirementStatus>) {
        _currentStep.value?.let { dismissStep(it) }
        refreshFromStatuses(statuses)
    }

    fun markNavigateToMain() {
        _shouldNavigateToMain.value = true
    }

    /**
     * 只允许前进：若当前步骤仍在 pending 中则保持；否则取 pending 第一项。
     * 绝不回退到 ordinal 更小的已 dismiss 步骤。
     */
    private fun pickForwardStep(pending: List<GuideStep>): GuideStep? {
        val current = _currentStep.value
        if (current != null && current in pending) return current
        if (current != null) {
            return pending.firstOrNull { it.ordinal > current.ordinal }
        }
        return pending.firstOrNull()
    }

    companion object {

        fun isNotificationApplicable(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
            sdkInt >= Build.VERSION_CODES.TIRAMISU

        fun filterPending(statuses: List<GuideRequirementStatus>): List<GuideStep> =
            statuses.filter { it.isPending }.map { it.step }

        fun areAllRequirementsSatisfied(statuses: List<GuideRequirementStatus>): Boolean =
            statuses.none { it.isPending }
    }
}
