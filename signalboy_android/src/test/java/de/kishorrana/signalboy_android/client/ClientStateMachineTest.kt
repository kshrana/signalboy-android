package de.kishorrana.signalboy_android.client

import org.junit.experimental.runners.Enclosed
import org.junit.runner.RunWith

private val logger = // mock<Logger>()
    object {
        fun log(message: String) {
            println(message)
        }
    }

private const val TAG = "ClientStateMachineTest"

@RunWith(Enclosed::class)
class ClientStateMachineTest {

//    private fun givenStateIs(state: State): StateMachine<State, Event, SideEffect> {
//        return stateMachine.with { initialState(state) }
//    }
}
