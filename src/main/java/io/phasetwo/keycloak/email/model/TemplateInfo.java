package io.phasetwo.keycloak.email.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Describes a Keycloak email type and the variables it makes available to templates. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Metadata about a Keycloak email type and the template variables it exposes")
public class TemplateInfo {

  @Schema(description = "Keycloak email type name, used as the key in the templates map", example = "password-reset")
  private String name;

  @Schema(description = "Human-readable description of when this email is sent")
  private String description;

  @Schema(description = "Variables available to the dynamic template for this email type. Every type also receives the universal base variables (realmName, userEmail, etc.)")
  private List<String> variables;
}
