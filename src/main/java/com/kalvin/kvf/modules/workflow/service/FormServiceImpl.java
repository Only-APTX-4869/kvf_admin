package com.kalvin.kvf.modules.workflow.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kalvin.kvf.common.exception.KvfException;
import com.kalvin.kvf.modules.workflow.config.Constant;
import com.kalvin.kvf.modules.workflow.entity.Form;
import com.kalvin.kvf.modules.workflow.mapper.FormMapper;
import com.kalvin.kvf.modules.workflow.utils.KeyGenKit;
import com.kalvin.kvf.modules.workflow.vo.FormConfigVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 * 表单设计表 服务实现类
 * </p>
 * @since 2020-05-02 19:36:28
 */
@Slf4j
@Service
public class FormServiceImpl extends ServiceImpl<FormMapper, Form> implements FormService {

    @Override
    public Page<Form> listFormPage(Form form) {
        Page<Form> page = new Page<>(form.getCurrent(), form.getSize());
        List<Form> forms = baseMapper.selectFormList(form, page);
        return page.setRecords(forms);
    }

    @Override
    public void saveForm(Form form) {
        String code;
        if (Constant.FORM_TYPE_SIMPLE == form.getType()) {
            code = KeyGenKit.getKey("FS_");
        } else if (Constant.FORM_TYPE_COMPLEX == form.getType()) {
            code = KeyGenKit.getKey("FC_");
        } else {
            throw new KvfException("无效的表单类型：" + form.getType());
        }
        form.setCode(code);
        super.save(form);
    }

    @Override
    public FormConfigVO getFormConfigById(Long id) {
        return this.getFormConfig(super.getById(id), null);
    }

    @Override
    public FormConfigVO getFormConfigByCode(String code) {
        return this.getFormConfig(this.getByCode(code), null);
    }

    @Override
    public FormConfigVO getFormConfig(String code, Map<String, Object> variables) {
        return this.getFormConfig(this.getByCode(code), variables);
    }

    @Override
    public FormConfigVO getFormConfig(Form form, Map<String, Object> variables) {
        if (form == null) {
            throw new KvfException("异常：form为空");
        }
        if (form.getType() != Constant.FORM_TYPE_SIMPLE) {
            throw new KvfException("只能使用简单表单类型数据");
        }
        String designData = form.getDesignData();
        designData = designData.replaceAll("&quot", "\"");
        log.debug("designData={}", designData);
        JSONObject jsonObject = JSONUtil.parseObj(designData, true, true);

        log.debug("jsonObject={}", jsonObject);

        final FormConfigVO formConfigVO = new FormConfigVO();
        formConfigVO.setName(form.getName());
        formConfigVO.setTheme(form.getTheme());
        Map<String, String> detailTableColumns = new HashMap<>();
        if (StrUtil.isNotBlank(form.getServiceBean()) && StrUtil.isNotBlank(form.getShowColumns()) && StrUtil.isNotBlank(form.getEntityClazz())) {
            formConfigVO.setHasDetailTable(true);
            formConfigVO.setServiceBean(form.getServiceBean());
            formConfigVO.setEntityClazz(form.getEntityClazz());
            String showColumns = form.getShowColumns();
            showColumns = showColumns.replaceAll("&quot", "\"");
            JSONArray objects = JSONUtil.parseArray(showColumns);
            detailTableColumns = objects.stream().collect(Collectors.toMap(o -> ((JSONObject) o).getStr("field"), o -> ((JSONObject) o).getStr("title")));
        }
        formConfigVO.setDetailTableColumns(detailTableColumns);
        formConfigVO.setDetailTableColumnsStr(JSONUtil.toJsonStr(detailTableColumns));

        final List<FormConfigVO.Field> fields = new ArrayList<>();

        Map<String, List<FormConfigVO.Field>> fieldGroups = new HashMap<>();
        jsonObject.keySet().forEach(key -> {
            final FormConfigVO.Field field = new FormConfigVO.Field();
            final JSONObject fieldObj = (JSONObject) jsonObject.get(key);
            final JSONObject obj = (JSONObject) fieldObj.get("options");
            final String fieldId = fieldObj.get("id").toString();
            final String fieldKey = fieldObj.get("field").toString();
            final String control = fieldObj.get("control").toString();
            final List<FormConfigVO.Option> options = new ArrayList<>();
            obj.keySet().forEach(oKey -> {
                final FormConfigVO.Option option = new FormConfigVO.Option();
                final JSONObject optionObj = (JSONObject) obj.get(oKey);
                option.setId(optionObj.get("id").toString());
                option.setFieldId(fieldId);
                option.setText(optionObj.get("text").toString());
                option.setValue(optionObj.get("value").toString());
                options.add(option);
            });
            field.setId(fieldId);
            field.setField(fieldKey);
            if (variables != null) {
                if ("checkbox".equals(control)) {
                    field.setValue(variables.get(fieldKey) == null ? "" : variables.get(fieldKey));
                } else {
                    field.setValue(variables.get(fieldKey));
                }
            }
            field.setFieldName(fieldObj.get("fieldName").toString());
            field.setControl(control);
            field.setGroupName(StrUtil.isBlank(fieldObj.getStr("groupName")) ? "表单信息" : fieldObj.getStr("groupName"));
            field.setRequired(Integer.valueOf(fieldObj.get("required").toString()));
            field.setOptions(options);

            // 分组
            List<FormConfigVO.Field> temFields = Optional.ofNullable(fieldGroups.get(field.getGroupName())).orElse(new ArrayList<>());
            temFields.add(field);
            fieldGroups.put(field.getGroupName(), temFields);

            fields.add(field);
        });

        formConfigVO.setFields(fields);
        formConfigVO.setFieldsGroups(fieldGroups);
        log.debug("formConfigVO={}", formConfigVO);
        return formConfigVO;
    }

    @Override
    public Form getByCode(String code) {
        return super.getOne(new LambdaQueryWrapper<Form>().eq(Form::getCode, code));
    }

}
