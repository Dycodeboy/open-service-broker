package com.swisscom.cloud.sb.model.backup

import groovy.transform.CompileStatic

@CompileStatic
class RestoreDto implements Serializable {
    String id
    String backup_id
    Date created_at
    Date updated_at
    RestoreStatus status
}
