package com.highmobility.hmkit.Command;

/**
 * Created by ttiganik on 16/12/2016.
 */

public class Constants {
    public enum WasherFluidLevel { LOW, FULL }

    /**
     * The possible charge states
     */
    public enum ChargingState {
        DISCONNECTED, PLUGGED_IN, CHARGING, CHARGING_COMPLETE;

        public static ChargingState fromByte(byte value) throws CommandParseException {
            switch (value) {
                case 0x00: return DISCONNECTED;
                case 0x01: return PLUGGED_IN;
                case 0x02: return CHARGING;
                case 0x03: return CHARGING_COMPLETE;
            }

            throw new CommandParseException();
        }
    }

    /**
     * The possible charge port states
     */
    public enum ChargePortState {
        CLOSED, OPEN, UNAVAILABLE;

        public static ChargePortState fromByte(byte value) throws CommandParseException {
            switch (value) {
                case 0x00: return CLOSED;
                case 0x01: return OPEN;
                case (byte)0xFF: return UNAVAILABLE;
            }

            throw new CommandParseException();
        }
    }
}
