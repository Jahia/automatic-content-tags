import React, {useState} from 'react';
import {Dialog, DialogActions, DialogContent, DialogTitle} from '@material-ui/core';
import {Button, Dropdown, Tag, Typography, Warning, Separator} from '@jahia/moonstone';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import styles from './AutoTagsDialog.scss';
import {LoaderOverlay} from '../DesignSystem/LoaderOverlay';

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
            <DialogTitle disableTypography id="dialog-autotags-title" className={styles.header}>
                <span className={styles.iconBadge}>
                    <Tag size="big"/>
                </span>
                <span className={styles.headerText}>
                    <Typography isUpperCase variant="heading" weight="bold" className={styles.title}>
                        {t('automatic-content-tags:label.dialog.dialogTitle')}
                    </Typography>
                    <Typography variant="body" className={styles.subtitle}>
                        {t('automatic-content-tags:label.dialog.dialogSubTitle')}
                    </Typography>
                </span>
            </DialogTitle>

            <Separator className={styles.separator}/>

            <DialogContent className={styles.content} classes={{root: styles.contentRoot}}>
                <LoaderOverlay
                    status={loadingQuery}
                    caption={t('automatic-content-tags:label.dialog.generating')}
                />
                <label className={styles.field}>
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
            </DialogContent>

            <Separator className={styles.separator}/>

            <DialogActions className={styles.footer}>
                <Typography className={`${styles.hint} ${errorMessage ? styles.hintError : ''}`}>
                    <Warning className={`${styles.hintIcon} ${errorMessage ? styles.hintIconError : ''}`}/>
                    {errorMessage || t('automatic-content-tags:label.dialog.bottomText')}
                </Typography>
                <div className={styles.actions}>
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
            </DialogActions>
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
