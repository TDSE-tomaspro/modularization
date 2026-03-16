package org.example.demo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@RestController
public class HelloController {

    @GetMapping("/")
    public String index() {
        try (InputStream input = HelloController.class.getResourceAsStream("/static/index.html")) {
            if (input == null) {
                return "No fue posible cargar index.html";
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return "Error cargando index.html";
        }
    }

    @GetMapping("/pi")
    public String getPI() {
        return "PI: "+ Math.PI;
    }

    @GetMapping("/hello")
    public String helloWorld() {
        return "Hello World";
    }
}
