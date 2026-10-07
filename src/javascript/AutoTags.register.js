import i18next from 'i18next';
import {registry} from '@jahia/ui-extender';
import {AutoTagsAction} from './AutoTags/AutoTagsAction';
import React from 'react';
import {Tag} from '@jahia/moonstone';

export default async function () {
    await i18next.loadNamespaces('automatic-content-tags');

    registry.add('action', 'automatic-content-tags', AutoTagsAction, {
        targets: ['content-editor/header/3dots:3.5'],
        buttonIcon: <Tag/>,
        buttonLabel: 'automatic-content-tags:label.title'
    });

    console.debug('%c automatic-content-tags is activated', 'color: #3c8cba');
}
