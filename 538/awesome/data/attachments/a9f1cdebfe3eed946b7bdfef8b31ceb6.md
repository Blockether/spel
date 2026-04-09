## PATCH http://localhost:32797/echo → 200 OK

### Request Headers
```
PATCH http://localhost:32797/echo
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
date: Thu, 09 Apr 2026 16:21:58 GMT
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
curl 'http://localhost:32797/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
