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

    @Column(updatable = false)
    @Generated
    private Instant createdAt;

    // E.164, written only by the verification together with phoneVerifiedAt (V8, guide 29, 2.4)
    @Column
    private String phone;

    @Column
    private Instant phoneVerifiedAt;

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

    public Instant getPhoneVerifiedAt() {
        return phoneVerifiedAt;
    }

    // only after the code sent to this number came back right (guide 29, part 3): no setter
    void verifyPhone(PhoneNumber number, Instant at) {
        this.phone = Objects.requireNonNull(number, "number must not be null").value();
        this.phoneVerifiedAt = Objects.requireNonNull(at, "at must not be null");
    }

    void removePhone() {
        this.phone = null;
        this.phoneVerifiedAt = null;
    }

    public boolean isActive() {
        return active;
    }
}
