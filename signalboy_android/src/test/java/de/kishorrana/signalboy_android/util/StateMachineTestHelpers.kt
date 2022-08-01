package de.kishorrana.signalboy_android.util

import com.natpryce.hamkrest.*
import com.tinder.StateMachine

/**
 * Returns a matcher that checks for equality of two valid transitions.
 */
internal fun <STATE : Any, EVENT : Any, SIDE_EFFECT : Any, T : StateMachine.Transition.Valid<STATE, EVENT, SIDE_EFFECT>> equalToValidTransition(
    fromState: STATE,
    event: EVENT,
    toState: STATE,
    sideEffect: SIDE_EFFECT?
) = object : Matcher<T> {
    private val hasEqualPropertyValues = allOf(
        has(
            StateMachine.Transition.Valid<STATE, EVENT, SIDE_EFFECT>::fromState,
            equalTo(fromState)
        ),
        has(
            StateMachine.Transition.Valid<STATE, EVENT, SIDE_EFFECT>::event,
            equalTo(event)
        ),
        has(
            StateMachine.Transition.Valid<STATE, EVENT, SIDE_EFFECT>::toState,
            equalTo(toState)
        ),
        has(
            StateMachine.Transition.Valid<STATE, EVENT, SIDE_EFFECT>::sideEffect,
            equalTo(sideEffect)
        )
    )

    override fun invoke(actual: T) =
        hasEqualPropertyValues(actual)

    override val description: String
        get() = hasEqualPropertyValues.description
}
