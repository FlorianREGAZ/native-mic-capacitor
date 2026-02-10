package com.memora.ai.nativemic;

import com.getcapacitor.Logger;

public class NativeMic {

    public String echo(String value) {
        Logger.info("Echo", value);
        return value;
    }
}
