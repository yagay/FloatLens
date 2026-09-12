package com.yagay.floatlens.hook;

final class FlInspectorConfig {
    static volatile boolean touch=true, gesture=true, actions=true, position=true, quickMove=true,
            preferences=true, window=true, environment=true, screenshot=true, circleOcr=true,
            lifecycle=true, methodProbe=false, objectDiff=true;
    static void setAll(boolean v){touch=gesture=actions=position=quickMove=preferences=window=environment=screenshot=circleOcr=lifecycle=v;}
    private FlInspectorConfig(){}
}
