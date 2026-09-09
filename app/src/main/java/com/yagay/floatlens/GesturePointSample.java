package com.yagay.floatlens;

/** Immutable sampled point from one floating-icon gesture session. */
public record GesturePointSample(float x, float y, long timeMs) {}
