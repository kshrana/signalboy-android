package de.kishorrana.signalboy_android

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.isA
import com.tinder.StateMachine
import de.kishorrana.signalboy_android.util.equalToValidTransition
import org.junit.Test
import org.junit.experimental.runners.Enclosed
import org.junit.runner.RunWith

@RunWith(Enclosed::class)
internal class StateMachineTest {
    class MatterStateMachine {
        private val logger = // mock<Logger>()
            object {
                fun log(message: String) {
                    println(message)
                }
            }

        private val stateMachine = StateMachine.create<State, Event, SideEffect> {
            initialState(State.Solid)
            state<State.Solid> {
                on<Event.OnMelted> {
                    transitionTo(State.Liquid, SideEffect.LogMelted)
                }
            }
            state<State.Liquid> {
                on<Event.OnFrozen> {
                    transitionTo(State.Solid, SideEffect.LogFrozen)
                }
                on<Event.OnVaporized> {
                    transitionTo(State.Gas, SideEffect.LogVaporized)
                }
            }
            state<State.Gas> {
                on<Event.OnCondensed> {
                    transitionTo(State.Liquid, SideEffect.LogCondensed)
                }
            }
            onTransition {
                val validTransition = it as? StateMachine.Transition.Valid ?: return@onTransition
                when (validTransition.sideEffect) {
                    SideEffect.LogMelted -> logger.log(ON_MELTED_MESSAGE)
                    SideEffect.LogFrozen -> logger.log(ON_FROZEN_MESSAGE)
                    SideEffect.LogVaporized -> logger.log(ON_VAPORIZED_MESSAGE)
                    SideEffect.LogCondensed -> logger.log(ON_CONDENSED_MESSAGE)
                }
            }
        }

        @Test
        fun initialState_shouldBeSolid() {
            // Then
            assertThat(stateMachine.state, equalTo(State.Solid))
        }

        @Test
        fun givenStateIsSolid_onMelted_shouldTransitionToLiquidStateAndLog() {
            // Given
            val stateMachine = givenStateIs(State.Solid)

            // When
            val transition = stateMachine.transition(Event.OnMelted)

            // Then
            assertThat(stateMachine.state, equalTo(State.Liquid))
            assertThat(transition, isA(equalToValidTransition(State.Solid, Event.OnMelted, State.Liquid, SideEffect.LogMelted)))
            // then(logger).should().log(ON_MELTED_MESSAGE)
        }

        private fun givenStateIs(state: State): StateMachine<State, Event, SideEffect> {
            return stateMachine.with { initialState(state) }
        }

        companion object {
            const val ON_MELTED_MESSAGE = "I melted"
            const val ON_FROZEN_MESSAGE = "I froze"
            const val ON_VAPORIZED_MESSAGE = "I vaporized"
            const val ON_CONDENSED_MESSAGE = "I condensed"

            sealed class State {
                object Solid : State()
                object Liquid : State()
                object Gas : State()
            }

            sealed class Event {
                object OnMelted : Event()
                object OnFrozen : Event()
                object OnVaporized : Event()
                object OnCondensed : Event()
            }

            sealed class SideEffect {
                object LogMelted : SideEffect()
                object LogFrozen : SideEffect()
                object LogVaporized : SideEffect()
                object LogCondensed : SideEffect()
            }

            interface Logger {
                fun log(message: String)
            }
        }
    }
}
