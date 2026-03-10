## PATCH http://localhost:44461/echo → 200 OK

### Request Headers
```
PATCH http://localhost:44461/echo
```

### Request Body
```json
{
  "email": "eve@new.com"
}
```

### Response Headers
```
content-length: 64
content-type: application/json
date: Tue, 10 Mar 2026 08:10:00 GMT
```

### Response Body
```json
{
  "method": "PATCH",
  "path": "/echo",
  "body": {
    "email": "eve@new.com"
  }
}
```

### cURL
```bash
curl 'http://localhost:44461/echo' \
  -X PATCH \
  -d '{"email":"eve@new.com"}'
```
