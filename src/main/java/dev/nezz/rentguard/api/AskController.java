package dev.nezz.rentguard.api;

import dev.nezz.rentguard.ai.InspectorAssistant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AskController {

    public record Question(@NotBlank @Size(max = 500) String question) {
    }

    private final InspectorAssistant assistant;

    public AskController(InspectorAssistant assistant) {
        this.assistant = assistant;
    }

    @PostMapping("/ask")
    public InspectorAssistant.Answer ask(@Valid @RequestBody Question q) {
        return assistant.ask(q.question());
    }
}
