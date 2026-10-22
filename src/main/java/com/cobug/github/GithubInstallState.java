package com.cobug.github;

import com.cobug.auth.User;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "github_install_states", indexes = @Index(name="idx_github_install_state_value", columnList="stateValue", unique=true))
public class GithubInstallState {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name="user_id", nullable=false)
    private User user;
    @Column(nullable=false, unique=true, length=120)
    private String stateValue;
    @Column(nullable=false)
    private LocalDateTime expiresAt;
    @Column(nullable=false)
    private boolean consumed;
    public UUID getId(){return id;}
    public User getUser(){return user;} public void setUser(User v){user=v;}
    public String getStateValue(){return stateValue;} public void setStateValue(String v){stateValue=v;}
    public LocalDateTime getExpiresAt(){return expiresAt;} public void setExpiresAt(LocalDateTime v){expiresAt=v;}
    public boolean isConsumed(){return consumed;} public void setConsumed(boolean v){consumed=v;}
}
