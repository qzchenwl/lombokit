package org.lombokit;

import java.util.List;

@ADT
public abstract class Value {

    public static class Scalar extends Value {
        public final Object x;

        public Scalar(Object x) {
            this.x = x;
        }

    }

    public static class Array extends Value {
        public final List<Object> xs;

        public Array(List<Object> xs) {
            this.xs = xs;
        }
    }
}
