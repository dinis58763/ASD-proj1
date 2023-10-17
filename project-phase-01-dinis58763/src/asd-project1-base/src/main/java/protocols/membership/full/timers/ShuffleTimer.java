package protocols.membership.full.timers;

import pt.unl.fct.di.novasys.babel.generic.ProtoTimer;

public class ShuffleTimer extends ProtoTimer {


    public static final short TIMER_ID = 103;

    public ShuffleTimer() {
        super(TIMER_ID);
    }

    @Override
    public ProtoTimer clone() {
        return this;
    }
}
