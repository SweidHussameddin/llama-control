package de.sweid.llamactl.api;

import de.sweid.llamactl.model.Profile;
import de.sweid.llamactl.service.ProfileService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** CRUD over named flag profiles. */
@RestController
@RequestMapping("/api/profiles")
public class ProfilesController {

  private final ProfileService service;

  /**
   * Wires the profile service.
   *
   * @param service the profile service
   */
  public ProfilesController(ProfileService service) {
    this.service = service;
  }

  /**
   * Lists every saved profile.
   *
   * @return profile list
   */
  @GetMapping
  public List<Profile> list() {
    return service.list();
  }

  /**
   * Loads a single profile by id.
   *
   * @param id profile id
   * @return the profile, or 404
   */
  @GetMapping("/{id}")
  public ResponseEntity<Profile> get(@PathVariable String id) {
    return service.get(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
  }

  /**
   * Creates a new profile (or overwrites if the id already exists).
   *
   * @param profile profile to persist
   * @return the persisted profile
   * @throws IOException on disk failure
   */
  @PostMapping
  public Profile create(@Valid @RequestBody Profile profile) throws IOException {
    return service.save(profile);
  }

  /**
   * Updates a profile by id; the {@code id} in the body must match the path.
   *
   * @param id path id
   * @param profile updated profile
   * @return the persisted profile, or 400 on id mismatch
   * @throws IOException on disk failure
   */
  @PutMapping("/{id}")
  public ResponseEntity<Profile> update(
      @PathVariable String id, @Valid @RequestBody Profile profile) throws IOException {
    if (!id.equals(profile.id())) {
      return ResponseEntity.badRequest().build();
    }
    return ResponseEntity.ok(service.save(profile));
  }

  /**
   * Deletes a profile by id.
   *
   * @param id profile id
   * @return 204 always (delete is idempotent)
   * @throws IOException on disk failure
   */
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable String id) throws IOException {
    service.delete(id);
    return ResponseEntity.noContent().build();
  }
}
