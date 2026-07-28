## DELETE http://localhost:41295/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Tue, 28 Jul 2026 15:07:25 GMT
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
curl 'http://localhost:41295/echo' \
  -X DELETE
```
