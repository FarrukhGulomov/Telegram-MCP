package io.tgmcp.ui;

import io.tgmcp.audit.AuditService;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AuditController {

    private final CurrentActor currentActor;
    private final AuditService auditService;

    public AuditController(CurrentActor currentActor, AuditService auditService) {
        this.currentActor = currentActor;
        this.auditService = auditService;
    }

    @GetMapping("/audit")
    public String audit(Model model) {
        model.addAttribute("events",
                this.auditService.recent(this.currentActor.require().tenantId(), 200));
        return "audit";
    }
}
