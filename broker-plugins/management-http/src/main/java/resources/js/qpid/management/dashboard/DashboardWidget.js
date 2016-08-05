/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
define(["dojo/_base/declare",
        "dojo/_base/lang",
        "dojo/json",
        "dojo/Evented",
        "dojo/text!dashboard/DashboardWidget.html",
        "dojo/text!dashboard/AddWidgetDialogContent.html",
        "dojo/text!dashboard/QueryWidgetSettings.html",
        "qpid/management/preference/PreferenceBrowserWidget",
        "qpid/management/preference/PreferenceSaveDialogContent",
        "dojox/uuid/generateRandomUuid",
        "dojo/promise/all",
        "dojo/Deferred",
        "dojox/layout/GridContainerLite",
        "dijit/_WidgetBase",
        "dijit/_TemplatedMixin",
        "dijit/_WidgetsInTemplateMixin",
        "dijit/form/Button",
        "dijit/Toolbar",
        "dijit/Dialog"],
    function (declare,
              lang,
              json,
              Evented,
              template,
              addWidgetDialogContentTemplate,
              queryWidgetSettingsTemplate,
              PreferenceBrowserWidget,
              PreferenceSaveDialogContent,
              generateRandomUuid,
              all,
              Deferred)
    {

        var AddWidgetDialogContent = declare("qpid.management.dashboard.AddWidgetDialogContent",
            [dijit._WidgetBase, dijit._TemplatedMixin, dijit._WidgetsInTemplateMixin, Evented],
            {
                //Strip out the apache comment header from the template html as comments unsupported.
                templateString: addWidgetDialogContentTemplate.replace(/<!--[\s\S]*?-->/g, ""),

                // template fields
                cancelButton: null,
                queryBrowserNode: null,

                // constructor mixed-in fields
                structure: null,
                management: null,
                preferenceRoot: null,

                // inner fields
                _queryBrowser: null,

                postCreate: function ()
                {
                    this.inherited(arguments);
                    this.cancelButton.on("click", lang.hitch(this, function ()
                    {
                        this.emit("cancel");
                    }));
                    this._queryBrowser = new PreferenceBrowserWidget({
                        structure: this.structure,
                        management: this.management,
                        preferenceRoot: this.preferenceRoot,
                        preferenceType: "query",
                        preferenceTypeFriendlyPlural: "queries",
                        preferenceTypeFriendlySingular: "Query"
                    }, this.queryBrowserNode);
                    this._queryBrowser.on("open", lang.hitch(this, this._onOpenQuery));
                },

                update: function ()
                {
                    return this._queryBrowser.update();
                },
                _onOpenQuery: function (event)
                {
                    var chosenWidget = {
                        type: "query",
                        preference: event.preference,
                        parentObject: event.parentObject,
                        settings: {type: "query", preference: {id: event.preference.id}},
                        id: generateRandomUuid()
                    };

                    this.emit("add", {widget: chosenWidget});
                }
            });

        return declare("qpid.management.dashboard.DashboardWidget",
            [dijit._WidgetBase, dijit._TemplatedMixin, dijit._WidgetsInTemplateMixin, Evented],
            {
                //Strip out the apache comment header from the template html as comments unsupported.
                templateString: template.replace(/<!--[\s\S]*?-->/g, ""),

                // template fields
                saveButton: null,
                cloneButton: null,
                deleteButton: null,
                addWidgetButton: null,
                widgetContainer: null,

                // constructor mixed in fields
                parentObject: null,
                preference: null,
                controller: null,
                management: null,

                // inner fields
                _addWidgetDialog: null,
                _addWidgetDialogContent: null,

                postCreate: function ()
                {
                    this.inherited(arguments);
                    this.deleteButton.set("disabled", true);
                    this.saveButton.on("click", lang.hitch(this, this._onSaveButton));
                    this.cloneButton.on("click", lang.hitch(this, this._onCloneButton));
                    this.deleteButton.on("click", lang.hitch(this, this._onDeleteButton));
                    this.addWidgetButton.on("click", lang.hitch(this, this._onAddWidget));
                    this._addWidgetDialogContent =
                        new AddWidgetDialogContent({
                            structure: this.controller.structure,
                            management: this.management,
                            preferenceRoot: this.parentObject
                        });
                    this._addWidgetDialog =
                        new dijit.Dialog({title: "Add Widget", content: this._addWidgetDialogContent});
                    this._addWidgetDialogContent.on("cancel",
                        lang.hitch(this._addWidgetDialog, this._addWidgetDialog.hide));
                    this._addWidgetDialogContent.on("add", lang.hitch(this, this._onWidgetChosen));

                    this._saveDashboardDialogContent = new PreferenceSaveDialogContent({management: this.management});
                    this._saveDashboardDialog =
                        new dijit.Dialog({title: "Save Dashboard", content: this._saveDashboardDialogContent});
                    this._saveDashboardDialogContent.on("cancel",
                        lang.hitch(this._saveDashboardDialog, this._saveDashboardDialog.hide));
                    this._saveDashboardDialogContent.on("save", lang.hitch(this, this._onPreferenceSave));

                    this.preference.type = "X-Dashboard";
                    this._verifyLayout();
                    this._loadPreferencesAndRestoreWidgets();
                },
                _onSaveButton: function ()
                {
                    this._saveDashboardDialogContent.set("preference", this.preference);
                    this._saveDashboardDialog.show();
                },
                _onCloneButton: function ()
                {
                    this.emit("clone", {parentObject: this.parentObject});
                },
                _onDeleteButton: function ()
                {
                    this.emit("delete", {preference: this.getDashboardPreference(), parentObject: this.parentObject});
                },
                _onPreferenceSave: function (event)
                {
                    var preference = event.preference;
                    this.management.savePreference(this.parentObject, preference)
                        .then(lang.hitch(this, function ()
                        {
                            this.preference = preference;
                            this._saveDashboardDialog.hide();
                            this.emit("save", {preference: this.preference});
                        }));
                },
                _onAddWidget: function ()
                {
                    this._addWidgetDialogContent.update()
                        .then(lang.hitch(this._addWidgetDialog, this._addWidgetDialog.show));
                },
                _onWidgetChosen: function (event)
                {
                    this._addWidgetDialog.hide();
                    var promise = this._createWidget(event.widget);
                    promise.then(lang.hitch(this, function(widget)
                    {
                        this._widgetChanged(widget);
                    }));
                },
                _createWidget: function (kwargs)
                {
                    var deferred = new Deferred();
                    require(["qpid/management/dashboard/widget/" + kwargs.type.toLowerCase()],
                        lang.hitch(this, function (Widget)
                        {
                            var widget = new Widget({
                                controller: this.controller,
                                management: this.management,
                                widgetSettings: kwargs.settings,
                                preference: kwargs.preference,
                                parentObject: kwargs.parentObject
                            });
                            widget.id = kwargs.id;
                            var portletPromise = widget.createPortlet();
                            portletPromise.then(lang.hitch(this, function (portlet)
                            {
                                this.widgetContainer.addChild(portlet);
                                portlet.startup();
                                widget.on("close", lang.hitch(this, function ()
                                {
                                    this.widgetContainer.removeChild(portlet);
                                    delete this.preference.value.widgets[widget.id];
                                    var position = this.preference.value.layout.column.indexOf(widget.id);
                                    this.preference.value.layout.column.splice(position, 1);
                                    widget.destroy();
                                    this._dashboardChanged();
                                }));

                                widget.on("change", lang.hitch(this, function ()
                                {
                                    this._widgetChanged(widget);
                                }));

                                deferred.resolve(widget);
                            }),
                            lang.hitch(this, function(error)
                            {
                                deferred.cancel(error);
                                this.management.errorHandler(error);
                            }));
                            
                            
                        }));
                    // todo: handle require load failure and cancel deferred
                    return deferred.promise;
                },
                _dashboardChanged: function ()
                {
                    this.emit("change", {preference: this.preference});
                },
                _verifyLayout: function ()
                {
                    if (!this.preference.value )
                    {
                        this.preference.value = {widgets: {}, layout: {type: "singleColumn", column: []}};
                    }
                    else if (!this.preference.value.layout || this.preference.value.layout.type !== "singleColumn")
                    {
                        var layout = {type: "singleColumn", column: []};
                        this.preference.value.layout = layout;
                        if (this.preference.value.widgets)
                        {
                            for (var id in this.preference.value.widgets)
                            {
                                layout.column.push(id);
                            }
                        }
                    }
                },
                _loadPreferencesAndRestoreWidgets: function()
                {
                    if (this.preference.value && this.preference.value.widgets)
                    {
                        var brokerPreferencesPromise = this.management.getVisiblePreferences({type: "broker"});
                        var virtualHostsPreferencesPromise = this.management.getVisiblePreferences({
                            type: "virtualhost",
                            name: "*",
                            parent: {
                                type: "virtualhostnode",
                                name: "*",
                                parent: {type: "broker"}
                            }
                        });

                        var resultPromise = all({
                            brokerPreferences: brokerPreferencesPromise,
                            virtualHostsPreferences: virtualHostsPreferencesPromise
                        });

                        resultPromise.then(lang.hitch(this, this._unwrapPreferencesAndRestoreWidgets));
                    }
                },
                _unwrapPreferencesAndRestoreWidgets: function (allPreferences)
                {
                    var preferences = {};

                    var unwrapPreferences = function (typePreferenceMap)
                    {
                        for (var type in typePreferenceMap)
                        {
                            if (typePreferenceMap.hasOwnProperty(type))
                            {
                                var typePreferences = typePreferenceMap[type];
                                for (var i = 0; i < typePreferences.length; i++)
                                {
                                    var preference = typePreferences[i];
                                    preferences[preference.id] = preference;
                                }
                            }
                        }
                    };

                    unwrapPreferences(allPreferences.brokerPreferences);
                    for (var i = 0; i < allPreferences.virtualHostsPreferences.length; i++)
                    {
                        unwrapPreferences(allPreferences.virtualHostsPreferences[i]);
                    }

                    this._restoreWidgets(preferences);
                },
                _restoreWidgets: function (preferences)
                {
                    for(var i = 0; i < this.preference.value.layout.column.length; i++)
                    {
                        var id = this.preference.value.layout.column[i];
                        var widgetSetting = this.preference.value.widgets[id];
                        if (widgetSetting && widgetSetting.preference && widgetSetting.preference.id)
                        {
                            var preference = preferences[widgetSetting.preference.id];
                            if (preference)
                            {
                                var parentObject = this.structure.findById(preference.associatedObject);
                                if (parentObject)
                                {
                                    this._createWidget({
                                        preference: preference,
                                        parentObject: parentObject,
                                        settings: widgetSetting,
                                        type: widgetSetting.type,
                                        id: id
                                    });
                                }
                                else
                                {
                                    // display special widget
                                }
                            }
                            else
                            {
                                delete this.preference.value.widgets[id];
                            }
                        }
                    }
                },
                _widgetChanged: function (widget)
                {
                    this.preference.value.widgets[widget.id] = widget.getSettings();
                    var index = this.preference.value.layout.column.indexOf(widget.id);
                    if (index === -1)
                    {
                        this.preference.value.layout.column.push(widget.id);
                    }
                    this._dashboardChanged();
                }
            });
    });