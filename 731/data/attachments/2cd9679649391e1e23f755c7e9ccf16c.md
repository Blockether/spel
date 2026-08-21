## DELETE http://localhost:35137/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Fri, 21 Aug 2026 02:16:11 GMT
```

### Response Body
```json
{
  "method": "DELETE",
  "path": "/echo"
}
```

### cURL
```bash
curl 'http://localhost:35137/echo' \
  -X DELETE
```
