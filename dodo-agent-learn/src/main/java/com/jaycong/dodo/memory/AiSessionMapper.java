// 将 ai_session 的 Mapper 放在记忆包中，使数据访问实现紧邻对应领域适配器。
package com.jaycong.dodo.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

// 让 MyBatis 扫描并注册该接口，同时继承 MyBatis-Plus 提供的通用 CRUD 操作。
@Mapper
public interface AiSessionMapper extends BaseMapper<AiSessionEntity> {
}
