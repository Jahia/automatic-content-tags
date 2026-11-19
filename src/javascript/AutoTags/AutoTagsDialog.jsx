import React, {useEffect, useState} from 'react';
import {Dialog} from '@material-ui/core';
import {Button, Checkbox, Dropdown, Input, Loader, Tag, Typography, Warning} from '@jahia/moonstone';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import styles from './AutoTagsDialog.scss';

const MIN_TAG_COUNT = 1;
// Used only until the server configuration is fetched (or if that fetch fails).
const FALLBACK_DEFAULT_TAG_COUNT = 5;
const FALLBACK_MAX_TAG_COUNT = 20;
// The default tag target (llm.tag.property=j:tagList, llm.tag.mixin=jmix:tagged), used until the server
// configuration is fetched (or if that fetch fails).
const FALLBACK_TAG_TARGET = {property: 'j:tagList', mixin: 'jmix:tagged', field: 'jmix:tagged_j:tagList', multiple: true};

const clampCount = (value, max, fallback) => {
    const parsed = parseInt(value, 10);
    if (Number.isNaN(parsed)) {
        return fallback;
    }

    return Math.min(max, Math.max(MIN_TAG_COUNT, parsed));
};

// The form field of the target: the one the server named, else a field of the form with the same property.
const resolveField = (values, target) => {
    const keys = Object.keys(values || {});
    if (keys.includes(target.field)) {
        return target.field;
    }

    return keys.find(key => key.endsWith(`_${target.property}`)) || target.field;
};

// A single-valued target holds the tags as one comma-separated string.
const splitTags = value => (typeof value === 'string' ? value.split(',').map(tag => tag.trim()).filter(Boolean) : value);

const mergeTags = (existing, generated) => {
    const merged = (Array.isArray(existing) ? existing : []).filter(tag => typeof tag === 'string');
    generated.forEach(tag => {
        if (!merged.some(current => current.toLowerCase() === tag.toLowerCase())) {
            merged.push(tag);
        }
    });
    return merged;
};

export const AutoTagsDialog = ({
    availableLanguages,
    isOpen,
    onCloseDialog,
    path,
    langLocale,
    formik
}) => {
    const {t} = useTranslation('automatic-content-tags');

    const defaultOption = {
        label: t('automatic-content-tags:label.dialog.defaultValue'),
        value: 'void'
    };

    const [currentOption, setCurrentOption] = useState(defaultOption);
    const [maxTags, setMaxTags] = useState(FALLBACK_MAX_TAG_COUNT);
    const [defaultTags, setDefaultTags] = useState(FALLBACK_DEFAULT_TAG_COUNT);
    const [tagCount, setTagCount] = useState(String(FALLBACK_DEFAULT_TAG_COUNT));
    const [replaceExisting, setReplaceExisting] = useState(true);
    const [loadingQuery, setLoadingQuery] = useState(false);
    const [errorMessage, setErrorMessage] = useState(null);
    const [tagTarget, setTagTarget] = useState(FALLBACK_TAG_TARGET);
    const [targetUnavailable, setTargetUnavailable] = useState(false);

    const contextPath = window.contextJsParameters?.contextPath || '';
    const actionBase = `${contextPath}/cms/editframe/default/${langLocale}${path}`;

    useEffect(() => {
        if (!isOpen) {
            return undefined;
        }

        let active = true;
        (async () => {
            try {
                const response = await fetch(`${actionBase}.contentTagsConfig.do`, {
                    method: 'POST',
                    headers: {Accept: 'application/json'}
                });
                if (!response.ok) {
                    return;
                }

                const config = await response.json();
                if (!active) {
                    return;
                }

                const max = Number.isFinite(config.maxTags) ? config.maxTags : FALLBACK_MAX_TAG_COUNT;
                const preset = Number.isFinite(config.defaultTags) ? config.defaultTags : FALLBACK_DEFAULT_TAG_COUNT;
                setMaxTags(max);
                setDefaultTags(preset);
                setTagCount(String(Math.min(max, preset)));
                if (config.tagError) {
                    setTargetUnavailable(true);
                    setErrorMessage(t('automatic-content-tags:label.dialog.targetError'));
                } else if (config.tagField && config.tagProperty) {
                    setTagTarget({
                        property: config.tagProperty,
                        mixin: config.tagMixin || null,
                        field: config.tagField,
                        multiple: config.tagMultiple !== false
                    });
                }
            } catch (error) {
                console.error('Unable to load Auto Tagging configuration, using defaults:', error);
            }
        })();

        return () => {
            active = false;
        };
    }, [actionBase, isOpen, t]);

    const handleCancel = () => {
        onCloseDialog();
    };

    const handleOnChange = (e, item) => {
        setCurrentOption(item);
        return true;
    };

    const handleClick = async () => {
        setLoadingQuery(true);
        setErrorMessage(null);
        try {
            const count = clampCount(tagCount, maxTags, defaultTags);
            const formData = new FormData();
            formData.append('tagLanguage', currentOption.label);
            formData.append('numberOfTags', String(count));

            const response = await fetch(`${actionBase}.generateContentTagsAction.do`, {
                method: 'POST',
                headers: {Accept: 'application/json'},
                body: formData
            });

            if (!response.ok) {
                throw new Error(`HTTP error, status: ${response.status}`);
            }

            const results = await response.json();
            if (Array.isArray(results.tags) && results.tags.length > 0) {
                const field = resolveField(formik.values, tagTarget);
                const nextTags = replaceExisting ?
                    results.tags :
                    mergeTags(splitTags(formik.values?.[field]), results.tags);
                if (tagTarget.mixin) {
                    await formik.setFieldValue(tagTarget.mixin, true);
                }

                await formik.setFieldValue(field, tagTarget.multiple ? nextTags : nextTags.join(', '));
                onCloseDialog();
            } else {
                setErrorMessage(t('automatic-content-tags:label.dialog.noTags'));
            }
        } catch (error) {
            console.error('Error generating tags:', error);
            setErrorMessage(t('automatic-content-tags:label.dialog.error'));
        } finally {
            setLoadingQuery(false);
        }
    };

    const isApplyDisabled = defaultOption.value === currentOption.value || loadingQuery || targetUnavailable;
    const hintText = replaceExisting ?
        t('automatic-content-tags:label.dialog.bottomTextReplace') :
        t('automatic-content-tags:label.dialog.bottomTextAppend');

    return (
        <Dialog fullWidth
                disableEnforceFocus
                disableAutoFocus
                disableRestoreFocus
                aria-labelledby="dialog-autotags-title"
                open={isOpen}
                maxWidth="sm"
                classes={{paper: styles.paper}}
                onClose={onCloseDialog}
        >
            <div className={styles.header} style={{display: 'flex', alignItems: 'center', gap: 16}}>
                <span className={styles.iconBadge}
                      style={{display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0}}
                >
                    <Tag/>
                </span>
                <span className={styles.headerText} style={{display: 'flex', flexDirection: 'column', minWidth: 0}}>
                    <Typography isUpperCase id="dialog-autotags-title" variant="heading" weight="bold" className={styles.title}>
                        {t('automatic-content-tags:label.dialog.dialogTitle')}
                    </Typography>
                    <Typography variant="body" className={styles.subtitle}>
                        {t('automatic-content-tags:label.dialog.dialogSubTitle')}
                    </Typography>
                </span>
            </div>

            <div className={styles.separator}/>

            <div className={styles.content}>
                <div className={styles.optionsRow} style={{display: 'flex', alignItems: 'flex-end', gap: 24}}>
                    <label className={styles.field} style={{display: 'flex', flexDirection: 'column', gap: 8, flexGrow: 1}}>
                        <Typography variant="subheading" weight="bold" className={styles.fieldLabel}>
                            {t('automatic-content-tags:label.dialog.listLabel')}
                        </Typography>
                        <Dropdown
                            className={styles.dropdown}
                            label={currentOption.label}
                            value={currentOption.value}
                            size="medium"
                            isDisabled={loadingQuery}
                            data={[defaultOption].concat(availableLanguages.map(element => {
                                return {
                                    value: element.language,
                                    label: element.displayName
                                };
                            }))}
                            onChange={handleOnChange}
                        />
                    </label>
                    <label className={styles.field} style={{display: 'flex', flexDirection: 'column', gap: 8}}>
                        <Typography variant="subheading" weight="bold" className={styles.fieldLabel}>
                            {t('automatic-content-tags:label.dialog.countLabel', {max: maxTags})}
                        </Typography>
                        <Input
                            className={styles.countInput}
                            type="number"
                            min={MIN_TAG_COUNT}
                            max={maxTags}
                            size="medium"
                            value={tagCount}
                            isDisabled={loadingQuery}
                            onChange={e => setTagCount(e.target.value)}
                            onBlur={() => setTagCount(String(clampCount(tagCount, maxTags, defaultTags)))}
                        />
                    </label>
                </div>

                <label className={styles.checkboxRow}
                       style={{display: 'flex', alignItems: 'center', gap: 8, marginTop: 20, cursor: 'pointer'}}
                >
                    <Checkbox
                        checked={replaceExisting}
                        isDisabled={loadingQuery}
                        onChange={(e, value, checked) => setReplaceExisting(checked)}
                    />
                    <Typography variant="body" className={styles.checkboxLabel}>
                        {t('automatic-content-tags:label.dialog.replaceLabel')}
                    </Typography>
                </label>
            </div>

            <div className={styles.separator}/>

            <div className={styles.footer} style={{display: 'flex', alignItems: 'center', gap: 16}}>
                <Typography
                    className={`${styles.hint} ${errorMessage ? styles.hintError : ''}`}
                    style={{display: 'flex', alignItems: 'center', gap: 8, flexGrow: 1}}
                >
                    {loadingQuery ? (
                        <>
                            <Loader size="small"/>
                            {t('automatic-content-tags:label.dialog.generating')}
                        </>
                    ) : (
                        <>
                            <Warning className={`${styles.hintIcon} ${errorMessage ? styles.hintIconError : ''}`}/>
                            {errorMessage || hintText}
                        </>
                    )}
                </Typography>
                <div className={styles.actions} style={{display: 'flex', gap: 8, flexShrink: 0}}>
                    <Button
                        size="big"
                        color="default"
                        label={t('automatic-content-tags:label.dialog.btnCancel')}
                        onClick={handleCancel}
                    />
                    <Button
                        size="big"
                        color="accent"
                        label={t('automatic-content-tags:label.dialog.btnApply')}
                        disabled={isApplyDisabled}
                        onClick={handleClick}
                    />
                </div>
            </div>
        </Dialog>
    );
};

AutoTagsDialog.propTypes = {
    availableLanguages: PropTypes.array,
    isOpen: PropTypes.bool,
    onCloseDialog: PropTypes.func,
    path: PropTypes.string,
    langLocale: PropTypes.string,
    formik: PropTypes.object
};
