package com.cobug.mainversion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.*;
public interface MainSourceObjectRepository extends JpaRepository<MainSourceObject, UUID> {
    Optional<MainSourceObject> findBySha256(String sha256);
    @Query("select o from MainSourceObject o where not exists (select f.id from MainVersionFile f where f.sourceObject.id = o.id)")
    List<MainSourceObject> findOrphanSourceObjects();
}
