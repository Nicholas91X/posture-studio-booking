package com.posturestudio.booking.auth;

import java.time.Instant;
import java.util.Objects;

import org.hibernate.annotations.Generated;
import jakarta.persistence.*;

@Entity 
@Table(name = "app_user")
public class AppUser {

    protected AppUser() {
        // JPA needs a no-arg constructor
    }

    public AppUser(Email email, Role role) {
        this.email = Objects.requireNonNull(email, "email must not be null");
        this.role = Objects.requireNonNull(role, "role must not be null");
        this.active = true;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column
    @Convert(converter = EmailConverter.class)
    private Email email;

    @Column
    @Enumerated(EnumType.STRING)
    private Role role;

    @Column
    @Generated
    private Instant createdAt;

    @Column
    private String phone;

    @Column
    private boolean active;

    // ***** METHODS *****
    public void deactivate() {
        this.active = false;
    }

    public void activate() {
        this.active = true;
    }

    public Long getId() {
        return id;
    }  

    public Email getEmail() {
        return email;
    }

    public Role getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public boolean isActive() {
        return active;
    }
}
