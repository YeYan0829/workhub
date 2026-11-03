package dev.workhub.onboarding;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class Retention {
    private final JdbcTemplate db;

    public Retention(JdbcTemplate db) {
        this.db = db;
    }

    @Scheduled(fixedDelay = 3600000)
    @Transactional
    public void cleanup() {
        db.update(
                "UPDATE csv_import_row r JOIN csv_import i ON i.id=r.import_id SET r.input=NULL"
                        + " WHERE i.expires_at<UTC_TIMESTAMP(6) AND r.input IS NOT NULL");
        db.update(
                "UPDATE csv_import SET source=NULL,state=IF(state IN"
                    + " ('PREVIEW','RUNNING'),'STOPPED',state),stop_reason=IF(state='STOPPED','IMPORT_EXPIRED',stop_reason)"
                    + " WHERE expires_at<UTC_TIMESTAMP(6) AND source IS NOT NULL");
        db.update(
                "DELETE FROM account_token WHERE expires_at<TIMESTAMPADD(DAY,-7,UTC_TIMESTAMP(6))");
        db.update("DELETE FROM rate_limit WHERE expires_at<UTC_TIMESTAMP(6)");
        db.update(
                "UPDATE mail_delivery SET encrypted_body=NULL,status='EXPIRED' WHERE"
                    + " created_at<TIMESTAMPADD(DAY,-7,UTC_TIMESTAMP(6)) AND encrypted_body IS NOT"
                    + " NULL");
    }
}
