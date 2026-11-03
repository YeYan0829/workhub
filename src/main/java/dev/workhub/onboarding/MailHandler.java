package dev.workhub.onboarding;

import static dev.workhub.common.Ids.*;

import dev.workhub.messaging.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class MailHandler implements RemoteWorkHandler {
    private final JdbcTemplate db;
    private final MailService mail;
    private final JavaMailSender sender;
    private final String from;

    public MailHandler(
            JdbcTemplate db,
            MailService mail,
            JavaMailSender sender,
            @org.springframework.beans.factory.annotation.Value(
                            "${workhub.mail.from:workhub@localhost}")
                    String from) {
        this.from = from;
        this.db = db;
        this.mail = mail;
        this.sender = sender;
    }

    public String kind() {
        return "MAIL_DELIVERY";
    }

    record Delivery(String to, String body) {}

    public Step prepare(WorkJobRepository.Job job) {
        var r = db.queryForMap("SELECT * FROM mail_delivery WHERE id=?", bytes(job.owner()));
        if (r.get("status").equals("SENT") || r.get("encrypted_body") == null)
            return Step.done("mail/" + job.owner());
        return Step.call(
                new Delivery(
                        (String) r.get("recipient"),
                        mail.decrypt((String) r.get("encrypted_body"))));
    }

    public Object perform(Object request) {
        try {
            var d = (Delivery) request;
            var m = new SimpleMailMessage();
            m.setFrom(from);
            m.setTo(d.to());
            m.setSubject("Work Hub 账号与团队通知");
            m.setText(d.body());
            sender.send(m);
            return true;
        } catch (org.springframework.mail.MailException e) {
            return false;
        }
    }

    public Step complete(WorkJobRepository.Job job, Object request, Object response) {
        if (Boolean.TRUE.equals(response)) {
            db.update(
                    "UPDATE mail_delivery SET"
                        + " status='SENT',sent_at=UTC_TIMESTAMP(6),encrypted_body=NULL,error_code=NULL"
                        + " WHERE id=?",
                    bytes(job.owner()));
            return Step.done("mail/" + job.owner());
        }
        db.update(
                "UPDATE mail_delivery SET error_code='SMTP_UNAVAILABLE' WHERE id=?",
                bytes(job.owner()));
        return Step.retryRead(Instant.now().plusSeconds(60));
    }
}
