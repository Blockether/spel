## PUT http://localhost:40585/echo → 200 OK

### Request Headers
```
PUT http://localhost:40585/echo
```

### Request Body
```json
{
  "name": "Eve Updated"
}
```

### Response Headers
```
content-length: 61
content-type: application/json
date: Wed, 25 Feb 2026 22:40:09 GMT
```

### Response Body
```json
{
  "method": "PUT",
  "path": "/echo",
  "body": {
    "name": "Eve Updated"
  }
}
```

### cURL
```bash
curl 'http://localhost:40585/echo' \
  -X PUT \
  -d '{"name":"Eve Updated"}'
```
