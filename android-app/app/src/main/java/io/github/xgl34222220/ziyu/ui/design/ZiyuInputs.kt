package io.github.xgl34222220.ziyu.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox as MaterialCheckbox
import androidx.compose.material3.CheckboxColors
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField as MaterialOutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton as MaterialRadioButton
import androidx.compose.material3.RadioButtonColors
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider as MaterialSlider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch as MaterialSwitch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextField as MaterialTextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.ziyu.ui.appearance.UiStyle
import io.github.xgl34222220.ziyu.ui.theme.LocalUiStyle
import io.github.suqi8.coui.kmp.basic.Checkbox as CouiCheckbox
import io.github.suqi8.coui.kmp.basic.CheckboxDefaults as CouiCheckboxDefaults
import io.github.suqi8.coui.kmp.basic.RadioButton as CouiRadioButton
import io.github.suqi8.coui.kmp.basic.RadioButtonDefaults as CouiRadioButtonDefaults
import io.github.suqi8.coui.kmp.basic.Slider as CouiSlider
import io.github.suqi8.coui.kmp.basic.SliderDefaults as CouiSliderDefaults
import io.github.suqi8.coui.kmp.basic.Switch as CouiSwitch
import io.github.suqi8.coui.kmp.basic.SwitchDefaults as CouiSwitchDefaults
import io.github.suqi8.coui.kmp.basic.TextField as CouiTextField
import io.github.suqi8.coui.kmp.basic.TextFieldDefaults as CouiTextFieldDefaults
import io.github.suqi8.coui.kmp.basic.TextFieldMode as CouiTextFieldMode
import io.github.suqi8.coui.kmp.theme.COUITheme
import top.yukonga.miuix.kmp.basic.Checkbox as MiuixCheckbox
import top.yukonga.miuix.kmp.basic.CheckboxDefaults as MiuixCheckboxDefaults
import top.yukonga.miuix.kmp.basic.RadioButton as MiuixRadioButton
import top.yukonga.miuix.kmp.basic.RadioButtonDefaults as MiuixRadioButtonDefaults
import top.yukonga.miuix.kmp.basic.Slider as MiuixSlider
import top.yukonga.miuix.kmp.basic.SliderDefaults as MiuixSliderDefaults
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch
import top.yukonga.miuix.kmp.basic.SwitchDefaults as MiuixSwitchDefaults
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults as MiuixTextFieldDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.squircle.squircleBorder

@Composable
fun ZiyuCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: CheckboxColors? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    when (LocalUiStyle.current) {
        UiStyle.MATERIAL -> MaterialCheckbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
            colors = colors ?: CheckboxDefaults.colors(),
            interactionSource = interactionSource,
        )
        UiStyle.MIUIX -> MiuixCheckbox(
            state = if (checked) ToggleableState.On else ToggleableState.Off,
            onClick = onCheckedChange?.let { callback -> { callback(!checked) } },
            modifier = modifier.ziyuMirrorPressInteractions(interactionSource, enabled),
            enabled = enabled,
            colors = colors?.let { material ->
                MiuixCheckboxDefaults.checkboxColors(
                    checkedForegroundColor = material.checkedCheckmarkColor,
                    uncheckedForegroundColor = material.uncheckedBorderColor,
                    disabledCheckedForegroundColor = material.disabledCheckedBoxColor,
                    disabledUncheckedForegroundColor = material.disabledUncheckedBorderColor,
                    checkedBackgroundColor = material.checkedBoxColor,
                    uncheckedBackgroundColor = material.uncheckedBoxColor,
                    disabledCheckedBackgroundColor = material.disabledCheckedBoxColor,
                    disabledUncheckedBackgroundColor = material.disabledUncheckedBoxColor,
                )
            } ?: MiuixCheckboxDefaults.checkboxColors(),
        )
        UiStyle.COUI -> CouiCheckbox(
            state = if (checked) ToggleableState.On else ToggleableState.Off,
            onClick = onCheckedChange?.let { callback -> { callback(!checked) } },
            modifier = modifier.ziyuMirrorPressInteractions(interactionSource, enabled),
            enabled = enabled,
            colors = colors?.let { material ->
                CouiCheckboxDefaults.checkboxColors(
                    checkedForegroundColor = material.checkedCheckmarkColor,
                    uncheckedForegroundColor = material.uncheckedBorderColor,
                    disabledCheckedForegroundColor = material.disabledCheckedBoxColor,
                    disabledUncheckedForegroundColor = material.disabledUncheckedBorderColor,
                    checkedBackgroundColor = material.checkedBoxColor,
                    uncheckedBackgroundColor = material.uncheckedBoxColor,
                    disabledCheckedBackgroundColor = material.disabledCheckedBoxColor,
                    disabledUncheckedBackgroundColor = material.disabledUncheckedBoxColor,
                )
            } ?: CouiCheckboxDefaults.checkboxColors(),
        )
    }
}

@Composable
fun ZiyuRadioButton(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: RadioButtonColors? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    when (LocalUiStyle.current) {
        UiStyle.MATERIAL -> MaterialRadioButton(
            selected = selected,
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = colors ?: RadioButtonDefaults.colors(),
            interactionSource = interactionSource,
        )
        UiStyle.MIUIX -> MiuixRadioButton(
            selected = selected,
            onClick = onClick,
            modifier = modifier.ziyuMirrorPressInteractions(interactionSource, enabled),
            enabled = enabled,
            colors = colors?.let {
                MiuixRadioButtonDefaults.radioButtonColors(
                    selectedColor = it.selectedColor,
                    disabledSelectedColor = it.disabledSelectedColor,
                )
            } ?: MiuixRadioButtonDefaults.radioButtonColors(),
        )
        UiStyle.COUI -> CouiRadioButton(
            selected = selected,
            onClick = onClick,
            modifier = modifier.ziyuMirrorPressInteractions(interactionSource, enabled),
            enabled = enabled,
            colors = colors?.let {
                CouiRadioButtonDefaults.radioButtonColors(
                    selectedColor = it.selectedColor,
                    disabledSelectedColor = it.disabledSelectedColor,
                    unselectedColor = it.unselectedColor,
                    disabledUnselectedColor = it.disabledUnselectedColor,
                    centerColor = it.selectedColor,
                    disabledCenterColor = it.disabledSelectedColor,
                )
            } ?: CouiRadioButtonDefaults.radioButtonColors(),
        )
    }
}

@Composable
fun ZiyuSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SwitchColors? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    when (LocalUiStyle.current) {
        UiStyle.MATERIAL -> MaterialSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
            colors = colors ?: SwitchDefaults.colors(),
            interactionSource = interactionSource,
        )
        UiStyle.MIUIX -> MiuixSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier.ziyuMirrorPressInteractions(interactionSource, enabled),
            enabled = enabled,
            colors = colors?.let {
                MiuixSwitchDefaults.switchColors(
                    checkedThumbColor = it.checkedThumbColor,
                    uncheckedThumbColor = it.uncheckedThumbColor,
                    disabledCheckedThumbColor = it.disabledCheckedThumbColor,
                    disabledUncheckedThumbColor = it.disabledUncheckedThumbColor,
                    checkedTrackColor = it.checkedTrackColor,
                    uncheckedTrackColor = it.uncheckedTrackColor,
                    disabledCheckedTrackColor = it.disabledCheckedTrackColor,
                    disabledUncheckedTrackColor = it.disabledUncheckedTrackColor,
                )
            } ?: MiuixSwitchDefaults.switchColors(),
        )
        UiStyle.COUI -> CouiSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier.ziyuMirrorPressInteractions(interactionSource, enabled),
            enabled = enabled,
            colors = colors?.let {
                CouiSwitchDefaults.switchColors(
                    checkedThumbColor = it.checkedThumbColor,
                    uncheckedThumbColor = it.uncheckedThumbColor,
                    disabledCheckedThumbColor = it.disabledCheckedThumbColor,
                    disabledUncheckedThumbColor = it.disabledUncheckedThumbColor,
                    checkedTrackColor = it.checkedTrackColor,
                    uncheckedTrackColor = it.uncheckedTrackColor,
                    disabledCheckedTrackColor = it.disabledCheckedTrackColor,
                    disabledUncheckedTrackColor = it.disabledUncheckedTrackColor,
                )
            } ?: CouiSwitchDefaults.switchColors(),
        )
    }
}

@Composable
fun ZiyuSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    when (LocalUiStyle.current) {
        UiStyle.MATERIAL -> MaterialSlider(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
            colors = colors ?: SliderDefaults.colors(),
            interactionSource = interactionSource ?: remember { MutableInteractionSource() },
        )
        UiStyle.MIUIX -> MiuixSlider(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
            colors = colors?.let {
                MiuixSliderDefaults.sliderColors(
                    foregroundColor = it.activeTrackColor,
                    disabledForegroundColor = it.disabledActiveTrackColor,
                    backgroundColor = it.inactiveTrackColor,
                    disabledBackgroundColor = it.disabledInactiveTrackColor,
                    thumbColor = it.thumbColor,
                    disabledThumbColor = it.disabledThumbColor,
                    keyPointColor = it.inactiveTickColor,
                    keyPointForegroundColor = it.activeTickColor,
                )
            } ?: MiuixSliderDefaults.sliderColors(),
        )
        UiStyle.COUI -> CouiSlider(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
            colors = colors?.let {
                CouiSliderDefaults.sliderColors(
                    foregroundColor = it.activeTrackColor,
                    disabledForegroundColor = it.disabledActiveTrackColor,
                    backgroundColor = it.inactiveTrackColor,
                    disabledBackgroundColor = it.disabledInactiveTrackColor,
                    thumbColor = it.thumbColor,
                    disabledThumbColor = it.disabledThumbColor,
                    keyPointColor = it.inactiveTickColor,
                    keyPointForegroundColor = it.activeTickColor,
                )
            } ?: CouiSliderDefaults.sliderColors(),
        )
    }
}

/** Shared search-field slot: retain a visible inset in every design system. */
@Composable
fun ZiyuSearchIconSlot(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.width(48.dp).padding(start = 16.dp, end = 8.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
fun ZiyuTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle? = null,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape? = null,
    colors: TextFieldColors? = null,
    labelText: String? = null,
    placeholderText: String? = null,
) = ZiyuTextFieldBase(
    outlined = false,
    stringValue = value,
    onStringValueChange = onValueChange,
    fieldValue = null,
    onFieldValueChange = null,
    modifier = modifier,
    enabled = enabled,
    readOnly = readOnly,
    textStyle = textStyle,
    label = label,
    placeholder = placeholder,
    leadingIcon = leadingIcon,
    trailingIcon = trailingIcon,
    prefix = prefix,
    suffix = suffix,
    supportingText = supportingText,
    isError = isError,
    visualTransformation = visualTransformation,
    keyboardOptions = keyboardOptions,
    keyboardActions = keyboardActions,
    singleLine = singleLine,
    maxLines = maxLines,
    minLines = minLines,
    interactionSource = interactionSource,
    shape = shape,
    colors = colors,
    labelText = labelText,
    placeholderText = placeholderText,
)

@Composable
fun ZiyuTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle? = null,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape? = null,
    colors: TextFieldColors? = null,
    labelText: String? = null,
    placeholderText: String? = null,
) = ZiyuTextFieldBase(
    outlined = false,
    stringValue = null,
    onStringValueChange = null,
    fieldValue = value,
    onFieldValueChange = onValueChange,
    modifier = modifier,
    enabled = enabled,
    readOnly = readOnly,
    textStyle = textStyle,
    label = label,
    placeholder = placeholder,
    leadingIcon = leadingIcon,
    trailingIcon = trailingIcon,
    prefix = prefix,
    suffix = suffix,
    supportingText = supportingText,
    isError = isError,
    visualTransformation = visualTransformation,
    keyboardOptions = keyboardOptions,
    keyboardActions = keyboardActions,
    singleLine = singleLine,
    maxLines = maxLines,
    minLines = minLines,
    interactionSource = interactionSource,
    shape = shape,
    colors = colors,
    labelText = labelText,
    placeholderText = placeholderText,
)

@Composable
fun ZiyuOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle? = null,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape? = null,
    colors: TextFieldColors? = null,
    labelText: String? = null,
    placeholderText: String? = null,
) = ZiyuTextFieldBase(
    outlined = true,
    stringValue = value,
    onStringValueChange = onValueChange,
    fieldValue = null,
    onFieldValueChange = null,
    modifier = modifier,
    enabled = enabled,
    readOnly = readOnly,
    textStyle = textStyle,
    label = label,
    placeholder = placeholder,
    leadingIcon = leadingIcon,
    trailingIcon = trailingIcon,
    prefix = prefix,
    suffix = suffix,
    supportingText = supportingText,
    isError = isError,
    visualTransformation = visualTransformation,
    keyboardOptions = keyboardOptions,
    keyboardActions = keyboardActions,
    singleLine = singleLine,
    maxLines = maxLines,
    minLines = minLines,
    interactionSource = interactionSource,
    shape = shape,
    colors = colors,
    labelText = labelText,
    placeholderText = placeholderText,
)

@Composable
fun ZiyuOutlinedTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle? = null,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape? = null,
    colors: TextFieldColors? = null,
    labelText: String? = null,
    placeholderText: String? = null,
) = ZiyuTextFieldBase(
    outlined = true,
    stringValue = null,
    onStringValueChange = null,
    fieldValue = value,
    onFieldValueChange = onValueChange,
    modifier = modifier,
    enabled = enabled,
    readOnly = readOnly,
    textStyle = textStyle,
    label = label,
    placeholder = placeholder,
    leadingIcon = leadingIcon,
    trailingIcon = trailingIcon,
    prefix = prefix,
    suffix = suffix,
    supportingText = supportingText,
    isError = isError,
    visualTransformation = visualTransformation,
    keyboardOptions = keyboardOptions,
    keyboardActions = keyboardActions,
    singleLine = singleLine,
    maxLines = maxLines,
    minLines = minLines,
    interactionSource = interactionSource,
    shape = shape,
    colors = colors,
    labelText = labelText,
    placeholderText = placeholderText,
)

@Composable
private fun ZiyuTextFieldBase(
    outlined: Boolean,
    stringValue: String?,
    onStringValueChange: ((String) -> Unit)?,
    fieldValue: TextFieldValue?,
    onFieldValueChange: ((TextFieldValue) -> Unit)?,
    modifier: Modifier,
    enabled: Boolean,
    readOnly: Boolean,
    textStyle: TextStyle?,
    label: @Composable (() -> Unit)?,
    placeholder: @Composable (() -> Unit)?,
    leadingIcon: @Composable (() -> Unit)?,
    trailingIcon: @Composable (() -> Unit)?,
    prefix: @Composable (() -> Unit)?,
    suffix: @Composable (() -> Unit)?,
    supportingText: @Composable (() -> Unit)?,
    isError: Boolean,
    visualTransformation: VisualTransformation,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
    singleLine: Boolean,
    maxLines: Int,
    minLines: Int,
    interactionSource: MutableInteractionSource?,
    shape: Shape?,
    colors: TextFieldColors?,
    labelText: String?,
    placeholderText: String?,
) {
    val style = LocalUiStyle.current
    when (style) {
        UiStyle.MATERIAL -> {
            val materialColors = colors ?: if (outlined) OutlinedTextFieldDefaults.colors() else TextFieldDefaults.colors()
            val materialShape = shape ?: if (outlined) OutlinedTextFieldDefaults.shape else TextFieldDefaults.shape
            val resolvedStyle = textStyle ?: LocalTextStyle.current
            if (stringValue != null && onStringValueChange != null) {
                if (outlined) MaterialOutlinedTextField(
                    value = stringValue, onValueChange = onStringValueChange, modifier = modifier,
                    enabled = enabled, readOnly = readOnly, textStyle = resolvedStyle,
                    label = label ?: labelText?.let { { MaterialTextFieldLabel(it) } },
                    placeholder = placeholder ?: placeholderText?.let { { MaterialTextFieldLabel(it) } },
                    leadingIcon = leadingIcon, trailingIcon = trailingIcon, prefix = prefix, suffix = suffix,
                    supportingText = supportingText, isError = isError,
                    visualTransformation = visualTransformation, keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions, singleLine = singleLine, maxLines = maxLines,
                    minLines = minLines, interactionSource = interactionSource, shape = materialShape,
                    colors = materialColors,
                ) else MaterialTextField(
                    value = stringValue, onValueChange = onStringValueChange, modifier = modifier,
                    enabled = enabled, readOnly = readOnly, textStyle = resolvedStyle,
                    label = label ?: labelText?.let { { MaterialTextFieldLabel(it) } },
                    placeholder = placeholder ?: placeholderText?.let { { MaterialTextFieldLabel(it) } },
                    leadingIcon = leadingIcon, trailingIcon = trailingIcon, prefix = prefix, suffix = suffix,
                    supportingText = supportingText, isError = isError,
                    visualTransformation = visualTransformation, keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions, singleLine = singleLine, maxLines = maxLines,
                    minLines = minLines, interactionSource = interactionSource, shape = materialShape,
                    colors = materialColors,
                )
            } else if (fieldValue != null && onFieldValueChange != null) {
                if (outlined) MaterialOutlinedTextField(
                    value = fieldValue, onValueChange = onFieldValueChange, modifier = modifier,
                    enabled = enabled, readOnly = readOnly, textStyle = resolvedStyle,
                    label = label ?: labelText?.let { { MaterialTextFieldLabel(it) } },
                    placeholder = placeholder ?: placeholderText?.let { { MaterialTextFieldLabel(it) } },
                    leadingIcon = leadingIcon, trailingIcon = trailingIcon, prefix = prefix, suffix = suffix,
                    supportingText = supportingText, isError = isError,
                    visualTransformation = visualTransformation, keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions, singleLine = singleLine, maxLines = maxLines,
                    minLines = minLines, interactionSource = interactionSource, shape = materialShape,
                    colors = materialColors,
                ) else MaterialTextField(
                    value = fieldValue, onValueChange = onFieldValueChange, modifier = modifier,
                    enabled = enabled, readOnly = readOnly, textStyle = resolvedStyle,
                    label = label ?: labelText?.let { { MaterialTextFieldLabel(it) } },
                    placeholder = placeholder ?: placeholderText?.let { { MaterialTextFieldLabel(it) } },
                    leadingIcon = leadingIcon, trailingIcon = trailingIcon, prefix = prefix, suffix = suffix,
                    supportingText = supportingText, isError = isError,
                    visualTransformation = visualTransformation, keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions, singleLine = singleLine, maxLines = maxLines,
                    minLines = minLines, interactionSource = interactionSource, shape = materialShape,
                    colors = materialColors,
                )
            }
        }
        UiStyle.MIUIX, UiStyle.COUI -> {
            val source = interactionSource ?: remember { MutableInteractionSource() }
            val focused by source.collectIsFocusedAsState()
            val nativeLabel = labelText ?: if (label == null && placeholder == null) placeholderText.orEmpty() else ""
            val useLabelAsPlaceholder = labelText == null && label == null && placeholder == null && placeholderText != null
            val errorSemantics = if (isError) {
                Modifier.semantics { error("输入无效") }
            } else Modifier
            val nativeModifier = Modifier.fillMaxWidth().then(errorSemantics)
            val materialTextColor = colors?.let {
                when {
                    !enabled -> it.disabledTextColor
                    isError -> it.errorTextColor
                    focused -> it.focusedTextColor
                    else -> it.unfocusedTextColor
                }
            }
            val inheritedColor = LocalZiyuContentColor.current
            val baseStyle = textStyle ?: LocalZiyuTextStyle.current ?: when (style) {
                UiStyle.MIUIX -> MiuixTheme.textStyles.main
                UiStyle.COUI -> COUITheme.textStyles.main
                else -> LocalTextStyle.current
            }
            val hasExplicitTextColor = textStyle?.color?.let { it != Color.Unspecified } == true
            val resolvedStyle = when {
                hasExplicitTextColor -> baseStyle
                materialTextColor != null -> baseStyle.copy(color = materialTextColor)
                // Keep inherited typography, but let the native field choose its default
                // foreground instead of inheriting a surrounding surface's content color.
                else -> baseStyle.copy(color = Color.Unspecified)
            }
            val customPlaceholder: (@Composable () -> Unit)? = placeholder ?: if (labelText != null || label != null) {
                placeholderText?.let {
                    {
                        val placeholderColor = if (style == UiStyle.MIUIX) {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        } else COUITheme.colorScheme.onSurfaceSecondary
                        ZiyuText(it, color = placeholderColor)
                    }
                }
            } else null
            val shownValue = stringValue ?: fieldValue?.text.orEmpty()
            Column(modifier = modifier) {
                if (label != null) {
                    CompositionLocalProvider(
                        LocalZiyuContentColor provides (inheritedColor ?: if (style == UiStyle.MIUIX) {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        } else COUITheme.colorScheme.onSurfaceSecondary),
                    ) { label() }
                }
                val miuixOutline = if (shape == null && style == UiStyle.MIUIX && outlined) {
                    Modifier.squircleBorder(
                        width = if (focused) 0.dp else 1.dp,
                        color = when {
                            isError -> MiuixTheme.colorScheme.error
                            focused -> Color.Transparent
                            else -> MiuixTheme.colorScheme.outline
                        },
                        cornerRadius = MiuixTextFieldDefaults.CornerRadius,
                    )
                } else Modifier
                val inputShapeChrome = when {
                    shape == null -> miuixOutline
                    style == UiStyle.MIUIX -> {
                        val customShape = requireNotNull(shape)
                        val containerColor = colors?.let {
                            if (focused) it.focusedContainerColor else it.unfocusedContainerColor
                        } ?: if (outlined) Color.Transparent else MiuixTheme.colorScheme.secondaryContainer
                        val borderWidth = animateDpAsState(
                            targetValue = when {
                                focused -> 2.dp // MIUIX 0.9.3 TextFieldDefaults.BorderWidth.
                                outlined -> 1.dp
                                else -> 0.dp
                            },
                            label = "ziyuMiuixTextFieldBorderWidth",
                        ).value
                        val borderColor = animateColorAsState(
                            targetValue = when {
                                focused -> colors?.let {
                                    if (isError) it.errorIndicatorColor else it.focusedIndicatorColor
                                } ?: if (isError) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary
                                outlined -> colors?.let {
                                    if (isError) it.errorIndicatorColor else it.unfocusedIndicatorColor
                                } ?: if (isError) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.outline
                                else -> Color.Transparent
                            },
                            label = "ziyuMiuixTextFieldBorderColor",
                        ).value
                        Modifier.fillMaxWidth()
                            .background(containerColor, customShape)
                            .border(borderWidth, borderColor, customShape)
                            .clip(customShape)
                    }
                    style == UiStyle.COUI && outlined -> {
                        val customShape = requireNotNull(shape)
                        val containerColor = colors?.let {
                            if (focused) it.focusedContainerColor else it.unfocusedContainerColor
                        } ?: Color.Transparent
                        val unfocusedBorderColor = colors?.unfocusedIndicatorColor
                            ?: COUITheme.colorScheme.outline
                        val borderWidth = animateDpAsState(
                            targetValue = if (enabled && (focused || isError)) 1.dp else 0.33.dp,
                            label = "ziyuCouiTextFieldBorderWidth",
                        ).value
                        val borderColor = animateColorAsState(
                            targetValue = when {
                                !enabled -> unfocusedBorderColor
                                isError -> colors?.errorIndicatorColor ?: COUITheme.colorScheme.error
                                focused -> colors?.focusedIndicatorColor ?: COUITheme.colorScheme.primary
                                else -> unfocusedBorderColor
                            },
                            label = "ziyuCouiTextFieldBorderColor",
                        ).value
                        Modifier.fillMaxWidth()
                            .background(containerColor, customShape)
                            .border(borderWidth, borderColor, customShape)
                            .clip(customShape)
                    }
                    else -> Modifier.fillMaxWidth().clip(requireNotNull(shape))
                }
                Box(modifier = inputShapeChrome) {
                    if (style == UiStyle.MIUIX) {
                        val miuixBackgroundColor = colors?.let {
                            if (focused) it.focusedContainerColor else it.unfocusedContainerColor
                        } ?: if (outlined) Color.Transparent else MiuixTheme.colorScheme.secondaryContainer
                        val miuixBorderColor = colors?.let {
                            if (isError) it.errorIndicatorColor else if (focused) it.focusedIndicatorColor else it.unfocusedIndicatorColor
                        } ?: when {
                            isError -> MiuixTheme.colorScheme.error
                            focused -> MiuixTheme.colorScheme.primary
                            outlined -> MiuixTheme.colorScheme.outline
                            else -> Color.Transparent
                        }
                        val nativeColors = MiuixTextFieldDefaults.textFieldColors(
                            backgroundColor = if (shape == null) miuixBackgroundColor else Color.Transparent,
                            labelColor = colors?.let { if (isError) it.errorLabelColor else if (focused) it.focusedLabelColor else it.unfocusedLabelColor }
                                ?: MiuixTheme.colorScheme.onSecondaryContainer,
                            borderColor = if (shape == null) miuixBorderColor else Color.Transparent,
                        )
                        if (stringValue != null && onStringValueChange != null) MiuixTextField(
                            value = stringValue, onValueChange = onStringValueChange, modifier = nativeModifier,
                            label = nativeLabel, useLabelAsPlaceholder = useLabelAsPlaceholder,
                            enabled = enabled, readOnly = readOnly, textStyle = resolvedStyle,
                            keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
                            leadingIcon = withPrefix(leadingIcon, prefix), trailingIcon = withSuffix(trailingIcon, suffix),
                            singleLine = singleLine, maxLines = maxLines, minLines = minLines,
                            visualTransformation = visualTransformation,
                            interactionSource = source, colors = nativeColors,
                            cursorBrush = SolidColor(miuixBorderColor),
                        ) else if (fieldValue != null && onFieldValueChange != null) MiuixTextField(
                            value = fieldValue, onValueChange = onFieldValueChange, modifier = nativeModifier,
                            label = nativeLabel, useLabelAsPlaceholder = useLabelAsPlaceholder,
                            enabled = enabled, readOnly = readOnly, textStyle = resolvedStyle,
                            keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
                            leadingIcon = withPrefix(leadingIcon, prefix), trailingIcon = withSuffix(trailingIcon, suffix),
                            singleLine = singleLine, maxLines = maxLines, minLines = minLines,
                            visualTransformation = visualTransformation,
                            interactionSource = source, colors = nativeColors,
                            cursorBrush = SolidColor(miuixBorderColor),
                        )
                    } else {
                        val couiBackgroundMode = when {
                            shape != null && outlined -> CouiTextFieldMode.None
                            outlined -> CouiTextFieldMode.Rectangle
                            else -> CouiTextFieldMode.Line
                        }
                        val couiInsideMargin = CouiTextFieldDefaults.insideMargin(
                            if (outlined) CouiTextFieldMode.Rectangle else CouiTextFieldMode.Line,
                        )
                        val couiBackgroundColor = colors?.let {
                            if (focused) it.focusedContainerColor else it.unfocusedContainerColor
                        } ?: if (outlined) Color.Transparent else COUITheme.colorScheme.surfaceContainer
                        val couiFocusedBorderColor = colors?.let {
                            if (isError) it.errorIndicatorColor else if (focused) it.focusedIndicatorColor else it.unfocusedIndicatorColor
                        } ?: when {
                            isError -> COUITheme.colorScheme.error
                            focused -> COUITheme.colorScheme.primary
                            outlined -> COUITheme.colorScheme.outline
                            else -> Color.Transparent
                        }
                        val couiUnfocusedBorderColor = colors?.unfocusedIndicatorColor
                            ?: if (outlined) COUITheme.colorScheme.outline else Color.Transparent
                        val nativeColors = CouiTextFieldDefaults.textFieldColors(
                            backgroundColor = if (shape != null && outlined) Color.Transparent else couiBackgroundColor,
                            labelColor = colors?.let { if (isError) it.errorLabelColor else if (focused) it.focusedLabelColor else it.unfocusedLabelColor }
                                ?: COUITheme.colorScheme.onSurfaceSecondary,
                            borderColor = if (shape != null && outlined) Color.Transparent else couiFocusedBorderColor,
                            unfocusedBorderColor = if (shape != null && outlined) Color.Transparent else couiUnfocusedBorderColor,
                            errorColor = colors?.errorIndicatorColor ?: COUITheme.colorScheme.error,
                            iconColor = colors?.focusedLeadingIconColor ?: COUITheme.colorScheme.onSurfaceSecondary,
                            disabledTextColor = colors?.disabledTextColor ?: COUITheme.colorScheme.disabledOnSurface,
                        )
                        if (stringValue != null && onStringValueChange != null) CouiTextField(
                            value = stringValue, onValueChange = onStringValueChange, modifier = nativeModifier,
                            backgroundMode = couiBackgroundMode, insideMargin = couiInsideMargin,
                            label = nativeLabel, useLabelAsPlaceholder = useLabelAsPlaceholder,
                            enabled = enabled, readOnly = readOnly, isError = isError,
                            textStyle = resolvedStyle, keyboardOptions = keyboardOptions,
                            keyboardActions = keyboardActions, leadingIcon = withPrefix(leadingIcon, prefix),
                            trailingIcon = withSuffix(trailingIcon, suffix), singleLine = singleLine,
                            maxLines = maxLines, minLines = minLines, visualTransformation = visualTransformation,
                            interactionSource = source, colors = nativeColors,
                            cursorBrush = SolidColor(couiFocusedBorderColor),
                        ) else if (fieldValue != null && onFieldValueChange != null) CouiTextField(
                            value = fieldValue, onValueChange = onFieldValueChange, modifier = nativeModifier,
                            backgroundMode = couiBackgroundMode, insideMargin = couiInsideMargin,
                            label = nativeLabel, useLabelAsPlaceholder = useLabelAsPlaceholder,
                            enabled = enabled, readOnly = readOnly, isError = isError,
                            textStyle = resolvedStyle, keyboardOptions = keyboardOptions,
                            keyboardActions = keyboardActions, leadingIcon = withPrefix(leadingIcon, prefix),
                            trailingIcon = withSuffix(trailingIcon, suffix), singleLine = singleLine,
                            maxLines = maxLines, minLines = minLines, visualTransformation = visualTransformation,
                            interactionSource = source, colors = nativeColors,
                            cursorBrush = SolidColor(couiFocusedBorderColor),
                        )
                    }
                    if (shownValue.isEmpty() && !focused && customPlaceholder != null) {
                        Box(
                            Modifier.matchParentSize().padding(start = if (leadingIcon != null) 48.dp else 18.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            CompositionLocalProvider(
                                LocalZiyuContentColor provides (inheritedColor ?: if (style == UiStyle.MIUIX) {
                                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                                } else COUITheme.colorScheme.onSurfaceSecondary),
                            ) { customPlaceholder() }
                        }
                    }
                }
                supportingText?.let {
                    Spacer(Modifier.height(4.dp))
                    CompositionLocalProvider(
                        LocalZiyuContentColor provides (
                            if (isError) {
                                if (style == UiStyle.MIUIX) MiuixTheme.colorScheme.error else COUITheme.colorScheme.error
                            } else inheritedColor ?: if (style == UiStyle.MIUIX) {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                            } else COUITheme.colorScheme.onSurfaceSecondary
                        ),
                    ) { it() }
                }
            }
        }
    }
}

@Composable
private fun withPrefix(
    icon: (@Composable () -> Unit)?,
    prefix: (@Composable () -> Unit)?,
): (@Composable () -> Unit)? = if (icon == null && prefix == null) null else {
    {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon?.invoke()
            if (icon != null && prefix != null) Spacer(Modifier.width(8.dp))
            prefix?.invoke()
        }
    }
}

@Composable
private fun withSuffix(
    icon: (@Composable () -> Unit)?,
    suffix: (@Composable () -> Unit)?,
): (@Composable () -> Unit)? = if (icon == null && suffix == null) null else {
    {
        Row(verticalAlignment = Alignment.CenterVertically) {
            suffix?.invoke()
            if (icon != null && suffix != null) Spacer(Modifier.width(8.dp))
            icon?.invoke()
        }
    }
}

@Composable
private fun MaterialTextFieldLabel(text: String) {
    androidx.compose.material3.Text(text)
}

/** Mirrors tap feedback into the caller's source when a native control owns a private source. */
internal fun Modifier.ziyuMirrorPressInteractions(
    source: MutableInteractionSource?,
    enabled: Boolean,
): Modifier = if (source == null || !enabled) this else pointerInput(source) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val press = PressInteraction.Press(down.position)
        source.tryEmit(press)
        val up = waitForUpOrCancellation()
        source.tryEmit(if (up == null) PressInteraction.Cancel(press) else PressInteraction.Release(press))
    }
}
