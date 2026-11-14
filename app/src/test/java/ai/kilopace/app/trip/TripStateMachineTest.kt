package ai.kilopace.app.trip

import ai.kilopace.app.trip.TripStateMachine.State
import ai.kilopace.app.trip.TripStateMachine.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripStateMachineTest {

    private fun sm() = TripStateMachine(stallMillis = 10_000L, cooldownMillis = 60_000L)

    @Test fun `idle ignores door and arrival`() {
        val m = sm()
        assertFalse(m.onDoor(true, 0).suggest)
        assertFalse(m.onArrived(0).suggest)
        assertEquals(State.IDLE, m.state)
    }

    @Test fun `start opens trip`() {
        val m = sm()
        assertTrue(m.userStart(1_000))
        assertEquals(State.OPEN, m.state)
        assertFalse(m.userStart(2_000))
    }

    @Test fun `door open before stall does not suggest`() {
        val m = sm(); m.userStart(0)
        m.onOdometer(100.0, 0)
        assertFalse(m.onDoor(true, 5_000).suggest)
        assertEquals(State.OPEN, m.state)
    }

    @Test fun `door open after stall suggests end`() {
        val m = sm(); m.userStart(0)
        m.onOdometer(100.0, 0)
        val r = m.onDoor(true, 12_000)
        assertTrue(r.suggest); assertEquals(Trigger.DOOR_STALL, r.trigger)
        assertEquals(State.CLOSING, m.state)
    }

    @Test fun `tick catches stall reached while door already open`() {
        val m = sm(); m.userStart(0)
        m.onOdometer(100.0, 0)
        assertFalse(m.onDoor(true, 3_000).suggest)
        assertFalse(m.tick(8_000).suggest)
        assertTrue(m.tick(10_000).suggest)
    }

    @Test fun `odometer change resets stall timer`() {
        val m = sm(); m.userStart(0)
        m.onOdometer(100.0, 0)
        m.onDoor(true, 1_000)
        m.onOdometer(100.5, 9_000)       // 짐 싣는 중 조금 이동
        assertFalse(m.tick(12_000).suggest)
        assertTrue(m.tick(19_000).suggest)
    }

    @Test fun `not parked blocks suggestion`() {
        val m = sm(); m.userStart(0)
        m.onOdometer(100.0, 0)
        m.onParked(false, 0)
        assertFalse(m.onDoor(true, 20_000).suggest)
        assertTrue(m.onParked(true, 21_000).suggest)
    }

    @Test fun `dismiss returns to open and applies cooldown`() {
        val m = sm(); m.userStart(0)
        m.onOdometer(100.0, 0)
        assertTrue(m.onDoor(true, 20_000).suggest)
        m.dismiss(20_000)
        assertEquals(State.OPEN, m.state)
        assertFalse(m.tick(50_000).suggest)      // 쿨다운 안
        assertTrue(m.tick(81_000).suggest)       // 쿨다운 지남
    }

    @Test fun `arrival suggests immediately regardless of door`() {
        val m = sm(); m.userStart(0)
        val r = m.onArrived(1_000)
        assertTrue(r.suggest); assertEquals(Trigger.ARRIVED, r.trigger)
    }

    @Test fun `user end then confirm goes idle`() {
        val m = sm(); m.userStart(0)
        assertEquals(Trigger.USER, m.userEnd(1_000).trigger)
        assertTrue(m.confirm())
        assertEquals(State.IDLE, m.state)
        assertFalse(m.confirm())
    }

    @Test fun `same odometer value does not refresh timer`() {
        val m = sm(); m.userStart(0)
        m.onOdometer(100.0, 0)
        m.onOdometer(100.0, 9_000)        // 반복 콜백(값 동일)
        assertTrue(m.onDoor(true, 10_000).suggest)
    }

    @Test fun `cancel trip from closing goes idle without confirm`() {
        val m = sm(); m.userStart(0)
        m.userEnd(1_000)
        m.cancelTrip()
        assertEquals(State.IDLE, m.state)
    }
}
