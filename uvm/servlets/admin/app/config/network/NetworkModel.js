Ext.define('Ung.config.network.NetworkModel', {
    extend: 'Ext.app.ViewModel',

    alias: 'viewmodel.config.network',

    formulas: {
        // used in view when showing/hiding interface specific configurations
        isAddressed: function (get) { return get('si.configType') === 'ADDRESSED'; },
        isDisabled: function (get) { return get('si.configType') === 'DISABLED'; },
        isBridged: function (get) { return get('si.configType') === 'BRIDGED'; },
        isStaticv4: function (get) { return get('si.v4ConfigType') === 'STATIC'; },
        isAutov4: function (get) { return get('si.v4ConfigType') === 'AUTO'; },
        isPPPOEv4: function (get) { return get('si.v4ConfigType') === 'PPPOE'; },
        isDisabledv6: function (get) { return get('si.v6ConfigType') === 'DISABLED'; },
        isStaticv6: function (get) { return get('si.v6ConfigType') === 'STATIC'; },
        isAutov6: function (get) { return get('si.v6ConfigType') === 'AUTO'; },
        showRouterWarning: function (get) { return get('si.v6StaticPrefixLength') !== 64; },
        showWireless: function (get) { return get('si.isWirelessInterface') && get('si.configType') !== 'DISABLED'; },
        showWirelessPassword: function (get) { return get('si.wirelessEncryption') !== 'NONE' && get('si.wirelessEncryption') !== null; },
        activePropsItem: function (get) { return get('si.configType') !== 'DISABLED' ? 0 : 2; },

        fullHostName: function (get) {
            var domain = get('settings.domainName'),
                host = get('settings.hostName');
            if (domain !== null && domain !== '') {
                return host + "." + domain;
            }
            return host;
        },

        // conditionsData: {
        //     bind: '{rule.conditions.list}',
        //     get: function (coll) {
        //         return coll || [];
        //     }
        // },
        portForwardRulesData: {
            bind: '{settings.portForwardRules.list}',
            get: function (rules) {
                return rules || null;
            }
        },

        natRulesData: {
            bind: '{settings.natRules.list}',
            get: function (rules) {
                return rules || null;
            }
        },

        qosPriorityNoDefaultStore: function (get) {
            return get('qosPriorityStore').slice(1);
        }
    },
    data: {
        // si = selected interface (from grid)
        settings: null,
        // si: null,
        siStatus: null,
        siArp: null,

        qosPriorityStore: [
            [0, 'Default'.t()],
            [1, 'Very High'.t()],
            [2, 'High'.t()],
            [3, 'Medium'.t()],
            [4, 'Low'.t()],
            [5, 'Limited'.t()],
            [6, 'Limited More'.t()],
            [7, 'Limited Severely'.t()]
        ],
    },
    stores: {
        // store which holds interfaces settings
        interfaces: {
            data: '{settings.interfaces.list}'
        },
        interfaceArp: {
            data: '{siArp}'
        },

        portforwardrules: {
            // type: 'rule',
            // data: '{settings.portForwardRules}'
            data: '{portForwardRulesData}'
        },

        natrules: {
            // type: 'rule',
            // data: '{settings.portForwardRules}'
            data: '{natRulesData}'
        },


    }
});