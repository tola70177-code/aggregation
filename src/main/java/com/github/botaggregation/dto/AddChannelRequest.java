package com.github.botaggregation.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AddChannelRequest {

    @NotNull
    private Long channelId;
}
