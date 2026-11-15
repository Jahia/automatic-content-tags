import React, {useContext} from 'react';
import PropTypes from 'prop-types';
import {AutoTagsDialog} from './AutoTagsDialog';
import {ComponentRendererContext} from '@jahia/ui-extender';
import {useNodeChecks} from '@jahia/data-helper';
import {useFormikContext} from 'formik';
import {useContentEditorContext} from '@jahia/jcontent';

export const AutoTagsActionComponent = ({render: Render, ...otherProps}) => {
    const {render, destroy} = useContext(ComponentRendererContext);
    const formik = useFormikContext();
    const {nodeData, lang, siteInfo} = useContentEditorContext();

    const {checksResult} = useNodeChecks(
        {path: nodeData.path},
        {
            showOnNodeTypes: ['jnt:content', 'jnt:page', 'jmix:mainResource', 'jmix:image'],
            requireModuleInstalledOnSite: ['automatic-content-tags']
        }
    );

    if (!checksResult) {
        return null;
    }

    return (
        <Render {...otherProps}
                enabled={nodeData.hasWritePermission}
                onClick={() => {
                    render('AutoTagsDialog', AutoTagsDialog, {
                        isOpen: true,
                        path: nodeData.path,
                        formik,
                        langLocale: lang,
                        availableLanguages: siteInfo.languages,
                        onCloseDialog: () => destroy('AutoTagsDialog')
                    });
                }}/>
    );
};

AutoTagsActionComponent.propTypes = {
    render: PropTypes.func.isRequired
};

export const AutoTagsAction = {
    component: AutoTagsActionComponent
};
