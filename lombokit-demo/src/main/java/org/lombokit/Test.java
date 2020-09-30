package org.lombokit;

import java.util.Arrays;

public class Test {
    public static void main(String[] args) {
        Value x = new Value.Array(Arrays.asList(123));
        String result = x.match(new Value.DefaultVisitor<String>() {
            public String caseScalar(Value.Scalar x) {
                return x.x.toString();
            }
            public String otherwise(Value x) {
                throw new RuntimeException("not supported");
            }
        });
        System.out.println(result);
    }
}
