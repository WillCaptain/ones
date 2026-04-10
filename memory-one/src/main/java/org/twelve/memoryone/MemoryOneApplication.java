package org.twelve.memoryone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.twelve.memoryone.config.LLMConfigProperties;

/**
 * Memory One — Standalone AIPP Memory Service.
 *
 * <p>Exposes the AIPP protocol endpoints:
 * <ul>
 *   <li>GET  /api/skills   — memory_load, memory_consolidate, memory_view, memory_set_instruction</li>
 *   <li>GET  /api/widgets  — memory-manager widget</li>
 *   <li>POST /api/tools/*  — memory CRUD + load + consolidate</li>
 * </ul>
 *
 * <p>Register into an AIPP Agent (e.g. worldone) by installing:
 * <pre>~/.ones/apps/memory-one/manifest.json:
 * { "id": "memory-one", "name": "Memory One", "api": { "base_url": "http://localhost:8091" } }
 * </pre>
 */
@SpringBootApplication
@EnableConfigurationProperties(LLMConfigProperties.class)
public class MemoryOneApplication {
    public static void main(String[] args) {
        SpringApplication.run(MemoryOneApplication.class, args);
    }
}
