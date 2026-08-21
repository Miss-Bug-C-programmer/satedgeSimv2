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
}
