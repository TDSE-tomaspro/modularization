package org.example.demo;

import java.util.concurrent.atomic.AtomicLong;

@RestController
public class GreetingController {

    private final AtomicLong counter = new AtomicLong();

    @GetMapping("/greeting")
    public String greeting(@RequestParam(value = "name", defaultValue = "World") String name) {
        long requestNumber = counter.incrementAndGet();
        return "Hola " + name + " (#" + requestNumber + ")";
    }
}