package edu.weijunyong.satedgesim.Network;

/** Fixture-only policy contract; it is not a live SatEdgeSim physical test. */
public final class MidTransferContactEnforcementFixtureTest {
    private MidTransferContactEnforcementFixtureTest() {
    }

    public static void main(String[] args) {
        require(!ContactEnforcementPolicy.shouldInterrupt(false, false, 10.0, 0.0),
                "local/non-contact transfer must not be interrupted");
        require(ContactEnforcementPolicy.shouldInterrupt(true, false, 0.0, 10.0),
                "missing required contact evidence must fail closed");
        require(!ContactEnforcementPolicy.shouldInterrupt(true, true, 4.9, 5.0),
                "active contact before its end must continue");
        require(ContactEnforcementPolicy.shouldInterrupt(true, true, 5.0, 5.0),
                "contact close must interrupt a remaining transfer");
        require("contact_window_closed_during_transfer".equals(
                ContactEnforcementPolicy.failureReason(true)), "closed-contact reason mismatch");
        require("contact_unavailable_at_transfer_start".equals(
                ContactEnforcementPolicy.failureReason(false)), "start-contact reason mismatch");
        System.out.println("MidTransferContactEnforcementFixtureTest OK (fixture-only)");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
