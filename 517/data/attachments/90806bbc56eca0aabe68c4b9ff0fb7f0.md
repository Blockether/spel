## POST http://localhost:32843/echo → 200 OK

### Request Headers
```
POST http://localhost:32843/echo
```

### Request Body
```json
{
  "name": "Eve",
  "action": "create"
}
```

### Response Headers
```
content-length: 72
content-type: application/json
date: Mon, 30 Mar 2026 11:55:39 GMT
```

### Response Body
```json
{
  "method": "POST",
  "path": "/echo",
  "body": {
    "name": "Eve",
    "action": "create"
  }
}
```

### cURL
```bash
curl 'http://localhost:32843/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
