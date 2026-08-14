// 项目类型与公司信息展示配置
// 说明：
// - 这里只做前端显示与表单配置，真正的字段含义和映射由后端接口负责。
// - 后续如果改为从后端读取配置，可以保持这份结构作为参考。

import { t } from '@/i18n'

export const PROJECT_TYPES = [
  {
    value: 'MAJOR_ASSET_RESTRUCTURING',
    label: t('config.projectTypes.majorAssetRestructuring'),
    formFields: [
      {
        field: 'listedCompanyName',
        label: t('config.projectFields.listedCompanyName'),
        required: true,
        placeholder: t('config.projectFields.listedCompanyNamePlaceholder'),
      },
      {
        field: 'targetCompanyName',
        label: t('config.projectFields.targetCompanyName'),
        required: true,
        placeholder: t('config.projectFields.targetCompanyNamePlaceholder'),
      },
    ],
    companyDisplay: {
      LISTED: { label: t('config.projectFields.listedCompanyInfo'), fields: [], lists: [] },
      TARGET: { label: t('config.projectFields.targetCompanyInfo'), fields: [], lists: [] }
    }
  },
  {
    value: 'PRIVATE_PLACEMENT',
    label: t('config.projectTypes.privatePlacement'),
    formFields: [
      {
        field: 'listedCompanyName',
        label: t('config.projectFields.listedCompanyName'),
        required: true,
        placeholder: t('config.projectFields.listedCompanyNamePlaceholder'),
      },
    ],
    companyDisplay: {
      LISTED: { label: t('config.projectFields.listedCompanyInfo'), fields: [], lists: [] },
      TARGET: null
    }
  },
  {
    value: 'PUBLIC_PLACEMENT',
    label: t('config.projectTypes.publicPlacement'),
    formFields: [
      {
        field: 'listedCompanyName',
        label: t('config.projectFields.listedCompanyName'),
        required: true,
        placeholder: t('config.projectFields.listedCompanyNamePlaceholder'),
      },
    ],
    companyDisplay: {
      LISTED: { label: t('config.projectFields.listedCompanyInfo'), fields: [], lists: [] },
      TARGET: null
    }
  },
  {
    value: 'ACQUISITION',
    label: t('config.projectTypes.acquisition'),
    formFields: [
      {
        field: 'listedCompanyName',
        label: t('config.projectFields.listedCompanyName'),
        required: true,
        placeholder: t('config.projectFields.listedCompanyNamePlaceholder'),
      },
      {
        field: 'targetCompanyName',
        label: t('config.projectFields.targetCompanyName'),
        required: true,
        placeholder: t('config.projectFields.targetCompanyNamePlaceholder'),
      },
    ],
    companyDisplay: {
      LISTED: { label: t('config.projectFields.listedCompanyInfo'), fields: [], lists: [] },
      TARGET: { label: t('config.projectFields.targetCompanyInfo'), fields: [], lists: [] }
    }
  },
  {
    value: 'BLANK',
    label: t('config.projectTypes.blank'),
    formFields: [
        {
            field: 'name',
            label: t('config.projectFields.projectName'),
            required: true,
            placeholder: t('config.projectFields.projectNamePlaceholder'),
        }
    ],
    companyDisplay: null
  }
];

export const COMPANY_ROLES = {
  LISTED: 'LISTED',
  TARGET: 'TARGET',
};

/**
 * 根据项目类型值获取显示标签
 */
export function getProjectTypeLabel(projectType) {
  const typeConfig = PROJECT_TYPES.find(t => t.value === projectType)
  return typeConfig ? typeConfig.label : projectType
}


