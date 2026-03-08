## PUT http://localhost:44523/echo → 200 OK

### Request Headers
```
PUT http://localhost:44523/echo
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
date: Sun, 08 Mar 2026 11:40:31 GMT
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
curl 'http://localhost:44523/echo' \
  -X PUT \
  -d '{"name":"Eve Updated"}'
```
