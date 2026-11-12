-- Transactional read model and bounded invalidation journal. Install with writers quiesced.
CREATE TABLE project_overview_state (
 project_id BINARY(16) PRIMARY KEY, view_seq BIGINT NOT NULL DEFAULT 0,
 topology_revision BIGINT NOT NULL DEFAULT 0, retained_after_seq BIGINT NOT NULL DEFAULT 0,
 triage_count BIGINT NOT NULL DEFAULT 0, todo_count BIGINT NOT NULL DEFAULT 0,
 in_progress_count BIGINT NOT NULL DEFAULT 0, done_count BIGINT NOT NULL DEFAULT 0,
 canceled_count BIGINT NOT NULL DEFAULT 0, total_edges BIGINT NOT NULL DEFAULT 0,
 FOREIGN KEY(project_id) REFERENCES project(id)
) ENGINE=InnoDB;
CREATE TABLE project_view_change (
 project_id BINARY(16) NOT NULL, seq BIGINT NOT NULL, kind VARCHAR(32) NOT NULL,
 task_id BINARY(16), blocker_id BINARY(16), blocked_id BINARY(16),
 status_changed BOOLEAN NOT NULL DEFAULT FALSE, scope_dirty BOOLEAN NOT NULL DEFAULT FALSE,
 changed_fields JSON NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(project_id,seq), INDEX(created_at,project_id,seq),
 FOREIGN KEY(project_id) REFERENCES project(id)
) ENGINE=InnoDB;
INSERT INTO project_overview_state(project_id,triage_count,todo_count,in_progress_count,done_count,canceled_count,total_edges)
 SELECT p.id,COALESCE(t.triage,0),COALESCE(t.todo,0),COALESCE(t.progress,0),COALESCE(t.done,0),COALESCE(t.canceled,0),COALESCE(d.edges,0)
 FROM project p LEFT JOIN
 (SELECT project_id,SUM(status='TRIAGE') triage,SUM(status='TODO') todo,SUM(status='IN_PROGRESS') progress,
 SUM(status='DONE') done,SUM(status='CANCELED') canceled FROM task GROUP BY project_id) t ON t.project_id=p.id
 LEFT JOIN (SELECT project_id,COUNT(*) edges FROM task_dependency GROUP BY project_id) d ON d.project_id=p.id;
CREATE INDEX overview_seeds ON task(project_id,status,priority,updated_at DESC,id DESC);
DELIMITER $$
CREATE PROCEDURE overview_change(IN p BINARY(16), IN k VARCHAR(32), IN t BINARY(16),
 IN a BINARY(16), IN b BINARY(16), IN topo BOOLEAN, IN status_dirty BOOLEAN, IN scope_dirty BOOLEAN, IN fields_json JSON)
BEGIN
 UPDATE project_overview_state SET view_seq=view_seq+1,topology_revision=topology_revision+topo WHERE project_id=p;
 INSERT INTO project_view_change(project_id,seq,kind,task_id,blocker_id,blocked_id,status_changed,scope_dirty,changed_fields)
 SELECT p,view_seq,k,t,a,b,status_dirty,scope_dirty,fields_json FROM project_overview_state WHERE project_id=p;
END$$
CREATE TRIGGER overview_project_insert AFTER INSERT ON project FOR EACH ROW
BEGIN
 INSERT INTO project_overview_state(project_id) VALUES (NEW.id);
END$$
CREATE TRIGGER overview_project_update AFTER UPDATE ON project FOR EACH ROW
BEGIN
 IF NOT(NEW.name<=>OLD.name) OR NOT(NEW.description<=>OLD.description) OR NEW.archived<>OLD.archived THEN
 CALL overview_change(NEW.id,'PROJECT_CHANGED',NULL,NULL,NULL,FALSE,FALSE,TRUE,JSON_ARRAY('project'));
 END IF;
END$$
CREATE TRIGGER overview_task_insert AFTER INSERT ON task FOR EACH ROW
BEGIN
 UPDATE project_overview_state SET triage_count=triage_count+(NEW.status='TRIAGE'),todo_count=todo_count+(NEW.status='TODO'),in_progress_count=in_progress_count+(NEW.status='IN_PROGRESS'),done_count=done_count+(NEW.status='DONE'),canceled_count=canceled_count+(NEW.status='CANCELED') WHERE project_id=NEW.project_id;
 CALL overview_change(NEW.project_id,'TASK_CREATED',NEW.id,NULL,NULL,TRUE,TRUE,FALSE,JSON_ARRAY('task'));
END$$
CREATE TRIGGER overview_task_delete AFTER DELETE ON task FOR EACH ROW
BEGIN
 UPDATE project_overview_state SET triage_count=triage_count-(OLD.status='TRIAGE'),todo_count=todo_count-(OLD.status='TODO'),in_progress_count=in_progress_count-(OLD.status='IN_PROGRESS'),done_count=done_count-(OLD.status='DONE'),canceled_count=canceled_count-(OLD.status='CANCELED') WHERE project_id=OLD.project_id;
 CALL overview_change(OLD.project_id,'TASK_REMOVED',OLD.id,NULL,NULL,TRUE,TRUE,FALSE,JSON_ARRAY('task'));
END$$
CREATE TRIGGER overview_task_update AFTER UPDATE ON task FOR EACH ROW
BEGIN
 IF NOT(NEW.title<=>OLD.title) OR NOT(NEW.description<=>OLD.description) OR NEW.status<>OLD.status
 OR NEW.type<>OLD.type OR NEW.priority<>OLD.priority OR NOT(NEW.assignee_id<=>OLD.assignee_id)
 OR NOT(NEW.due_date<=>OLD.due_date) OR NEW.triage_required<>OLD.triage_required THEN
 IF NEW.status<>OLD.status THEN
 UPDATE project_overview_state SET triage_count=triage_count-(OLD.status='TRIAGE'),todo_count=todo_count-(OLD.status='TODO'),in_progress_count=in_progress_count-(OLD.status='IN_PROGRESS'),done_count=done_count-(OLD.status='DONE'),canceled_count=canceled_count-(OLD.status='CANCELED') WHERE project_id=NEW.project_id;
 UPDATE project_overview_state SET triage_count=triage_count+(NEW.status='TRIAGE'),todo_count=todo_count+(NEW.status='TODO'),in_progress_count=in_progress_count+(NEW.status='IN_PROGRESS'),done_count=done_count+(NEW.status='DONE'),canceled_count=canceled_count+(NEW.status='CANCELED') WHERE project_id=NEW.project_id;
 END IF;
 CALL overview_change(NEW.project_id,'TASK_CHANGED',NEW.id,NULL,NULL,FALSE,NEW.status<>OLD.status,FALSE,
 JSON_ARRAY('title','description','status','type','priority','assignee','dueDate','triageRequired'));
 END IF;
END$$
CREATE TRIGGER overview_dependency_insert AFTER INSERT ON task_dependency FOR EACH ROW
BEGIN
 UPDATE project_overview_state SET total_edges=total_edges+1 WHERE project_id=NEW.project_id;
 CALL overview_change(NEW.project_id,'DEPENDENCY_CHANGED',NULL,NEW.blocker_id,NEW.blocked_id,TRUE,TRUE,FALSE,JSON_ARRAY('dependency'));
END$$
CREATE TRIGGER overview_dependency_delete AFTER DELETE ON task_dependency FOR EACH ROW
BEGIN
 UPDATE project_overview_state SET total_edges=total_edges-1 WHERE project_id=OLD.project_id;
 CALL overview_change(OLD.project_id,'DEPENDENCY_CHANGED',NULL,OLD.blocker_id,OLD.blocked_id,TRUE,TRUE,FALSE,JSON_ARRAY('dependency'));
END$$
CREATE TRIGGER overview_comment_insert AFTER INSERT ON comment FOR EACH ROW
BEGIN
 CALL overview_change(NEW.project_id,'DISCUSSION_CHANGED',NEW.task_id,NULL,NULL,FALSE,FALSE,FALSE,JSON_ARRAY('comment'));
END$$
CREATE TRIGGER overview_comment_update AFTER UPDATE ON comment FOR EACH ROW
BEGIN
 IF NOT(NEW.body<=>OLD.body) OR NEW.deleted<>OLD.deleted THEN
 CALL overview_change(NEW.project_id,'DISCUSSION_CHANGED',NEW.task_id,NULL,NULL,FALSE,FALSE,FALSE,JSON_ARRAY('comment'));
 END IF;
END$$
CREATE TRIGGER overview_comment_delete AFTER DELETE ON comment FOR EACH ROW
BEGIN
 CALL overview_change(OLD.project_id,'DISCUSSION_CHANGED',OLD.task_id,NULL,NULL,FALSE,FALSE,FALSE,JSON_ARRAY('comment'));
END$$
CREATE TRIGGER overview_project_document_insert AFTER INSERT ON project_document FOR EACH ROW
BEGIN
 CALL overview_change(NEW.project_id,'RESOURCE_CHANGED',NULL,NULL,NULL,FALSE,FALSE,TRUE,JSON_ARRAY('project_document'));
END$$
CREATE TRIGGER overview_project_document_update AFTER UPDATE ON project_document FOR EACH ROW
BEGIN
 IF NOT(NEW.body<=>OLD.body) OR NOT(NEW.title<=>OLD.title) OR NEW.deleted<>OLD.deleted OR NEW.kind<>OLD.kind THEN
 CALL overview_change(NEW.project_id,'RESOURCE_CHANGED',NULL,NULL,NULL,FALSE,FALSE,TRUE,JSON_ARRAY('project_document'));
 END IF;
END$$
CREATE TRIGGER overview_project_document_delete AFTER DELETE ON project_document FOR EACH ROW
BEGIN
 CALL overview_change(OLD.project_id,'RESOURCE_CHANGED',NULL,NULL,NULL,FALSE,FALSE,TRUE,JSON_ARRAY('project_document'));
END$$
CREATE TRIGGER overview_task_document_insert AFTER INSERT ON task_document FOR EACH ROW
BEGIN
 CALL overview_change(NEW.project_id,'RESOURCE_CHANGED',NEW.task_id,NULL,NULL,FALSE,FALSE,FALSE,JSON_ARRAY('task_document'));
END$$
CREATE TRIGGER overview_task_document_delete AFTER DELETE ON task_document FOR EACH ROW
BEGIN
 CALL overview_change(OLD.project_id,'RESOURCE_CHANGED',OLD.task_id,NULL,NULL,FALSE,FALSE,FALSE,JSON_ARRAY('task_document'));
END$$
CREATE TRIGGER overview_task_resource_insert AFTER INSERT ON task_resource FOR EACH ROW
BEGIN
 CALL overview_change(NEW.project_id,'RESOURCE_CHANGED',NEW.task_id,NULL,NULL,FALSE,FALSE,FALSE,JSON_ARRAY('task_resource'));
END$$
CREATE TRIGGER overview_task_resource_delete AFTER DELETE ON task_resource FOR EACH ROW
BEGIN
 CALL overview_change(OLD.project_id,'RESOURCE_CHANGED',OLD.task_id,NULL,NULL,FALSE,FALSE,FALSE,JSON_ARRAY('task_resource'));
END$$
CREATE TRIGGER overview_project_member_insert AFTER INSERT ON project_member FOR EACH ROW
BEGIN
 CALL overview_change(NEW.project_id,'ACCESS_CHANGED',NULL,NULL,NULL,FALSE,FALSE,TRUE,JSON_ARRAY('project_member'));
END$$
CREATE TRIGGER overview_project_member_update AFTER UPDATE ON project_member FOR EACH ROW
BEGIN
 IF NEW.active<>OLD.active OR NEW.role<>OLD.role THEN
 CALL overview_change(NEW.project_id,'ACCESS_CHANGED',NULL,NULL,NULL,FALSE,FALSE,TRUE,JSON_ARRAY('project_member'));
 END IF;
END$$
CREATE TRIGGER overview_project_member_delete AFTER DELETE ON project_member FOR EACH ROW
BEGIN
 CALL overview_change(OLD.project_id,'ACCESS_CHANGED',NULL,NULL,NULL,FALSE,FALSE,TRUE,JSON_ARRAY('project_member'));
END$$
DELIMITER ;
