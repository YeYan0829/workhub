package dev.workhub.work;

/** Shared projection semantics; authorization remains exclusively in business services. */
public final class TaskSemantics {
  private TaskSemantics(){}
  public static String priorityLabel(int priority){return switch(priority){case 0->"最高";case 1->"高";case 2->"普通";case 3->"低";default->"未定义";};}
  public static boolean dependencyRisk(TaskStatus status,boolean blocked){return status==TaskStatus.DONE && blocked;}
  public static final String READ_GUIDE="""
      优先级P0=最高、P1=高、P2=普通、P3=低。isBlocked表示存在状态非DONE的直接上游，CANCELED仍算阻塞。
      阻塞本身不禁止标DONE；DONE且isBlocked=true时dependencyRisk=true。DONE不要求CI成功，系统没有项目发布门禁。
      上述为字段和依赖规则，不是当前用户的写权限判定；状态修改仍受成员、角色、任务来源和接纳等业务权限约束。
      当前状态、规则允许与建议是否执行须区分；本Agent不能写入。没有记录不等于不存在、失败或未完成。
      """;
}
