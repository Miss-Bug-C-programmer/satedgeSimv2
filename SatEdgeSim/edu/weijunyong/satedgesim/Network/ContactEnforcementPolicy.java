package edu.weijunyong.satedgesim.Network;

/** Pure fail-closed policy used by the native transfer progress path. */
public final class ContactEnforcementPolicy {
    private ContactEnforcementPolicy() {
    }

    public static boolean shouldInterrupt(boolean contactRequired, boolean evidenceAvailable,
            double nowSec, double contactEndSec) {
        if (!contactRequired) return false;
        if (!evidenceAvailable) return true;
        return nowSec >= contactEndSec - 1.0e-9;
    }

    public static String failureReason(boolean evidenceAvailable) {
        return evidenceAvailable
                ? "contact_window_closed_during_transfer"
                : "contact_unavailable_at_transfer_start";
    }

    /**
     * A qualifying native mid-transfer event requires physical progress before
     * the contact boundary and non-zero work remaining at that boundary.  A
     * fail-closed transfer rejected before its first byte is not such an event.
     */
    public static boolean isQualifyingMidTransfer(double transferredKbits, double remainingKbits) {
        return transferredKbits > 1.0e-9 && remainingKbits > 1.0e-9;
    }
}
