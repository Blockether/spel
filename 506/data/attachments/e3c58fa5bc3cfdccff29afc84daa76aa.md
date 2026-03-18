## PATCH http://localhost:33415/echo → 200 OK

### Request Headers
```
PATCH http://localhost:33415/echo
```

### Request Body
```json
{
  "email": "alice3@example.org"
}
```

### Response Headers
```
content-length: 71
content-type: application/json
date: Wed, 18 Mar 2026 16:44:13 GMT
```

### Response Body
```json
{
  "method": "PATCH",
  "path": "/echo",
  "body": {
    "email": "alice3@example.org"
  }
}
```

### cURL
```bash
curl 'http://localhost:33415/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
