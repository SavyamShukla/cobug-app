package com.cobug.projects.files;

import java.util.List;

public record ProjectTreeNodeResponse(

        String id,

        String name,

        String path,

        String type,

        String content,

        List<ProjectTreeNodeResponse> children

) {
}
