package com.cobug.mainversion;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "main_version_files", uniqueConstraints = @UniqueConstraint(name = "uk_main_version_path", columnNames = {"version_id", "path"}))
public class MainVersionFile {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "version_id", nullable = false) private MainVersion version;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "source_object_id", nullable = false) private MainSourceObject sourceObject;
    @Column(nullable = false, length = 1000) private String path;
    public MainVersionFile() {}
    public UUID getId(){return id;} public MainVersion getVersion(){return version;} public void setVersion(MainVersion v){version=v;}
    public MainSourceObject getSourceObject(){return sourceObject;} public void setSourceObject(MainSourceObject v){sourceObject=v;}
    public String getPath(){return path;} public void setPath(String v){path=v;}
}
