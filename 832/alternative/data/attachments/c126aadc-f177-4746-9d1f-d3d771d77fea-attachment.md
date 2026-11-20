## DELETE http://localhost:33433/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Fri, 20 Nov 2026 07:44:19 GMT
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
curl 'http://localhost:33433/echo' \
  -X DELETE
```
