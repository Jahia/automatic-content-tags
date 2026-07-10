import React, {useState} from 'react';
import {Dialog} from '@material-ui/core';
import {Button, Dropdown, Loader, Tag, Typography, Warning} from '@jahia/moonstone';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import styles from './AutoTagsDialog.scss';

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
    const [loadingQuery, setLoadingQuery] = useState(false);
    const [errorMessage, setErrorMessage] = useState(null);

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
            const formData = new FormData();
            formData.append('tagLanguage', currentOption.label);

            const contextPath = window.contextJsParameters?.contextPath || '';
            const response = await fetch(`${contextPath}/cms/editframe/default/${langLocale}${path}.generateContentTagsAction.do`, {
                method: 'POST',
                headers: {Accept: 'application/json'},
                body: formData
            });

            if (!response.ok) {
                throw new Error(`HTTP error, status: ${response.status}`);
            }

            const results = await response.json();
            if (Array.isArray(results.tags) && results.tags.length > 0) {
                await formik.setFieldValue('jmix:tagged', true);
                await formik.setFieldValue('jmix:tagged_j:tagList', results.tags);
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

    const isApplyDisabled = defaultOption.value === currentOption.value || loadingQuery;

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
                <label className={styles.field} style={{display: 'flex', flexDirection: 'column', gap: 8}}>
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
                            {errorMessage || t('automatic-content-tags:label.dialog.bottomText')}
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
