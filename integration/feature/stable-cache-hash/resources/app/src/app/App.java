package app;

import com.google.common.collect.ImmutableList;

public class App {
    public static void main(String[] args) {
        ImmutableList<String> list = ImmutableList.of("hello", "world");
        System.out.println(list);
    }
}
