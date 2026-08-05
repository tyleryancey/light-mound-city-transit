"""Schema-aware GTFS-Realtime decoder, hand-rolled over the wire format.
Field numbers transcribed from gtfs-realtime.proto (v2.0). No protobuf runtime.
This doubles as the executable reference for the Kotlin port."""
import struct, collections, json

def _varint(b,i):
    r=0;s=0
    while True:
        x=b[i]; i+=1; r|=(x&0x7f)<<s; s+=7
        if not x&0x80: return r,i

def _s32(v):
    v &= (1<<64)-1
    return v-(1<<64) if v>=(1<<63) else v

def fields(b, s=0, e=None):
    """yield (field_number, wire_type, value_bytes_or_int)"""
    e = len(b) if e is None else e
    i=s
    while i<e:
        key,i=_varint(b,i); fn,wt=key>>3,key&7
        if wt==0: v,i=_varint(b,i); yield fn,wt,v
        elif wt==1: yield fn,wt,b[i:i+8]; i+=8
        elif wt==2:
            ln,i=_varint(b,i); yield fn,wt,b[i:i+ln]; i+=ln
        elif wt==5: yield fn,wt,b[i:i+4]; i+=4
        else: raise ValueError(f"unsupported wire type {wt} @ {i}")

def _msg(b, spec, unknown):
    """spec: {fn: (name, kind, repeated)} kind in str,u64,s32,u32,bool,f32,f64,enum,or a nested spec dict"""
    out={}
    for fn,wt,v in fields(b):
        if fn not in spec:
            unknown.append((spec.get('__name__','?'), fn, wt, len(v) if isinstance(v,(bytes,bytearray)) else v))
            continue
        name,kind,rep = spec[fn]
        if kind=='str': val=v.decode('utf-8')
        elif kind in ('u64','u32','bool','enum'): val=v
        elif kind=='s32': val=_s32(v)
        elif kind=='f32': val=struct.unpack('<f',v)[0]
        elif kind=='f64': val=struct.unpack('<d',v)[0]
        else: val=_msg(v, kind, unknown)
        if rep: out.setdefault(name,[]).append(val)
        else: out[name]=val
    return out

TRANSLATION={'__name__':'Translation',1:('text','str',0),2:('language','str',0)}
TSTRING   ={'__name__':'TranslatedString',1:('translation',TRANSLATION,1)}
TRIPDESC  ={'__name__':'TripDescriptor',1:('trip_id','str',0),2:('start_time','str',0),3:('start_date','str',0),
            4:('schedule_relationship','enum',0),5:('route_id','str',0),6:('direction_id','u32',0)}
VEHDESC   ={'__name__':'VehicleDescriptor',1:('id','str',0),2:('label','str',0),3:('license_plate','str',0),
            4:('wheelchair_accessible','enum',0)}
POSITION  ={'__name__':'Position',1:('latitude','f32',0),2:('longitude','f32',0),3:('bearing','f32',0),
            4:('odometer','f64',0),5:('speed','f32',0)}
STE       ={'__name__':'StopTimeEvent',1:('delay','s32',0),2:('time','s32',0),3:('uncertainty','s32',0)}
STU       ={'__name__':'StopTimeUpdate',1:('stop_sequence','u32',0),2:('arrival',STE,0),3:('departure',STE,0),
            4:('stop_id','str',0),5:('schedule_relationship','enum',0),6:('departure_occupancy_status','enum',0),
            7:('stop_time_properties',{'__name__':'StopTimeProperties',1:('assigned_stop_id','str',0)},0)}
TRIPUPD   ={'__name__':'TripUpdate',1:('trip',TRIPDESC,0),2:('stop_time_update',STU,1),3:('vehicle',VEHDESC,0),
            4:('timestamp','u64',0),5:('delay','s32',0)}
VEHPOS    ={'__name__':'VehiclePosition',1:('trip',TRIPDESC,0),2:('position',POSITION,0),
            3:('current_stop_sequence','u32',0),4:('current_status','enum',0),5:('timestamp','u64',0),
            6:('congestion_level','enum',0),7:('stop_id','str',0),8:('vehicle',VEHDESC,0),
            9:('occupancy_status','enum',0),10:('occupancy_percentage','u32',0)}
TIMERANGE ={'__name__':'TimeRange',1:('start','u64',0),2:('end','u64',0)}
ENTSEL    ={'__name__':'EntitySelector',1:('agency_id','str',0),2:('route_id','str',0),3:('route_type','s32',0),
            4:('trip',TRIPDESC,0),5:('stop_id','str',0),6:('direction_id','u32',0)}
ALERT     ={'__name__':'Alert',1:('active_period',TIMERANGE,1),5:('informed_entity',ENTSEL,1),6:('cause','enum',0),
            7:('effect','enum',0),8:('url',TSTRING,0),10:('header_text',TSTRING,0),11:('description_text',TSTRING,0),
            12:('tts_header_text',TSTRING,0),13:('tts_description_text',TSTRING,0),14:('severity_level','enum',0),
            15:('image',{'__name__':'LocalizedImage'},0),16:('image_alternative_text',TSTRING,0),
            17:('cause_detail',TSTRING,0),18:('effect_detail',TSTRING,0)}
ENTITY    ={'__name__':'FeedEntity',1:('id','str',0),2:('is_deleted','bool',0),3:('trip_update',TRIPUPD,0),
            4:('vehicle',VEHPOS,0),5:('alert',ALERT,0),6:('shape',{'__name__':'Shape'},0),
            7:('stop',{'__name__':'Stop'},0),8:('trip_modifications',{'__name__':'TripModifications'},0)}
HEADER    ={'__name__':'FeedHeader',1:('gtfs_realtime_version','str',0),2:('incrementality','enum',0),
            3:('timestamp','u64',0),4:('feed_version','str',0)}
FEED      ={'__name__':'FeedMessage',1:('header',HEADER,0),2:('entity',ENTITY,1)}

CAUSE={1:'UNKNOWN_CAUSE',2:'OTHER_CAUSE',3:'TECHNICAL_PROBLEM',4:'STRIKE',5:'DEMONSTRATION',6:'ACCIDENT',
       7:'HOLIDAY',8:'WEATHER',9:'MAINTENANCE',10:'CONSTRUCTION',11:'POLICE_ACTIVITY',12:'MEDICAL_EMERGENCY'}
EFFECT={1:'NO_SERVICE',2:'REDUCED_SERVICE',3:'SIGNIFICANT_DELAYS',4:'DETOUR',5:'ADDITIONAL_SERVICE',
        6:'MODIFIED_SERVICE',7:'OTHER_EFFECT',8:'UNKNOWN_EFFECT',9:'STOP_MOVED',10:'NO_EFFECT',11:'ACCESSIBILITY_ISSUE'}
SR={0:'SCHEDULED',1:'SKIPPED',2:'NO_DATA',3:'UNSCHEDULED'}
TSR={0:'SCHEDULED',1:'ADDED',2:'UNSCHEDULED',3:'CANCELED',5:'REPLACEMENT',6:'DUPLICATED',7:'DELETED'}
STATUS={0:'INCOMING_AT',1:'STOPPED_AT',2:'IN_TRANSIT_TO'}

def decode(path):
    b=open(path,'rb').read(); unknown=[]
    return _msg(b, FEED, unknown), unknown, len(b)
